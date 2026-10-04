/*
    Copyright 2016-2026 melonDS team

    This file is part of melonDS.

    melonDS is free software: you can redistribute it and/or modify it under
    the terms of the GNU General Public License as published by the Free
    Software Foundation, either version 3 of the License, or (at your option)
    any later version.

    melonDS is distributed in the hope that it will be useful, but WITHOUT ANY
    WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
    FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.

    You should have received a copy of the GNU General Public License along
    with melonDS. If not, see http://www.gnu.org/licenses/.
*/

// Android copy of melonDS src/net/LAN.{h,cpp} with the Wi-Fi/hotspot improvements
// (see the upstream patch). Renamed so it can coexist with the core library's LAN class.

#include <stdio.h>
#include <string.h>
#include <algorithm>
#include <chrono>

#ifdef __WIN32__
    #include <winsock2.h>
    #include <ws2tcpip.h>

    #define socket_t    SOCKET
    #define sockaddr_t  SOCKADDR
    #define sockaddr_in_t  SOCKADDR_IN
#else
    #include <unistd.h>
    #include <netinet/in.h>
    #include <sys/select.h>
    #include <sys/socket.h>
    #include <ifaddrs.h>
    #include <net/if.h>

    #define socket_t    int
    #define sockaddr_t  struct sockaddr
    #define sockaddr_in_t  struct sockaddr_in
    #define closesocket close
#endif

#ifndef INVALID_SOCKET
    #define INVALID_SOCKET  (socket_t)-1
#endif

#include "WifiLAN.h"


namespace melonDS
{

const u32 kDiscoveryMagic = 0x444E414C; // LAND
const u32 kLANMagic = 0x504E414C; // LANP
const u32 kPacketMagic = 0x4946494E; // NIFI

const u32 kProtocolVersion = 1;

const u32 kLocalhost = 0x0100007F;

enum
{
    Chan_Cmd = 0,           // channel 0 -- control commands
    Chan_MP,                // channel 1 -- MP data exchange
};

enum
{
    Cmd_ClientInit = 1,     // 01 -- host->client -- init new client and assign ID
    Cmd_PlayerInfo,         // 02 -- client->host -- send client player info to host
    Cmd_PlayerList,         // 03 -- host->client -- broadcast updated player list
    Cmd_PlayerConnect,      // 04 -- both -- signal connected state (ready to receive MP frames)
    Cmd_PlayerDisconnect,   // 05 -- both -- signal disconnected state (not receiving MP frames)

    // extension commands -- older melonDS versions silently ignore unknown commands,
    // so these keep the wire protocol compatible in both directions
    Cmd_Caps = 0x40,        // 40 -- both -- advertise optional capabilities to a peer
};

const u32 kCapsMagic = 0x53504143; // CAPS
const u32 kCapsVersion = 1;

enum
{
    Cap_Dedupe = (1 << 0),  // peer discards duplicate MP frames, so it's safe to send redundant copies
};

// ENet's congestion throttle randomly drops *unreliable* packets whenever the
// round-trip time rises above its recent average. All DS wireless frames are sent
// unreliable, so on Wi-Fi every latency bump made ENet discard MP frames -- exactly
// when the link is most fragile -- and the game saw that as missed replies.
// Pin the throttle fully open. This also sends a THROTTLE_CONFIGURE command so the
// remote end stops throttling toward us, even if it runs an older melonDS.
static void DisableThrottle(ENetPeer* peer)
{
    enet_peer_throttle_configure(peer, ENET_PEER_PACKET_THROTTLE_INTERVAL,
                                 ENET_PEER_PACKET_THROTTLE_SCALE, 0);
    peer->packetThrottle = ENET_PEER_PACKET_THROTTLE_SCALE;
}

// The Android platform layer doesn't implement Platform::GetUSCount()
static u64 MonotonicUS()
{
    using namespace std::chrono;
    return (u64)duration_cast<microseconds>(steady_clock::now().time_since_epoch()).count();
}

static u32 HashBytes(const u8* data, size_t len)
{
    // FNV-1a
    u32 h = 0x811C9DC5;
    for (size_t i = 0; i < len; i++)
    {
        h ^= data[i];
        h *= 0x01000193;
    }
    return h;
}

const int kDiscoveryPort = 7063;
const int kLANPort = 7064;


WifiLAN::WifiLAN() noexcept : Inited(false)
{
    DiscoveryMutex = Platform::Mutex_Create();
    PlayersMutex = Platform::Mutex_Create();

    DiscoverySocket = INVALID_SOCKET;
    DiscoveryLastTick = 0;

    Active = false;
    IsHost = false;
    Host = nullptr;
    //Lag = false;

    memset(RemotePeers, 0, sizeof(RemotePeers));
    memset(Players, 0, sizeof(Players));
    NumPlayers = 0;
    MaxPlayers = 0;

    ConnectedBitmask = 0;

    MPRecvTimeout = 25;
    LastHostID = -1;
    LastHostPeer = nullptr;

    FrameCount = 0;

    memset(PeerCaps, 0, sizeof(PeerCaps));
    memset(Seen, 0, sizeof(Seen));
    SeenPos = 0;
    AwaitReply = false;
    ReplyArrived = false;
    AwaitStartMs = 0;
    memset(AwaitMAC, 0, sizeof(AwaitMAC));
    ResetStats();

    // TODO make this somewhat nicer
    if (enet_initialize() != 0)
    {
        Platform::Log(Platform::LogLevel::Error, "LAN: failed to initialize enet\n");
        return;
    }

    Platform::Log(Platform::LogLevel::Info, "LAN: enet initialized\n");
    Inited = true;
}

WifiLAN::~WifiLAN() noexcept
{
    EndSession();

    Inited = false;
    enet_deinitialize();

    Platform::Mutex_Free(DiscoveryMutex);
    Platform::Mutex_Free(PlayersMutex);

    Platform::Log(Platform::LogLevel::Info, "LAN: enet deinitialized\n");
}


std::map<u32, WifiLAN::DiscoveryData> WifiLAN::GetDiscoveryList()
{
    Platform::Mutex_Lock(DiscoveryMutex);
    auto ret = DiscoveryList;
    Platform::Mutex_Unlock(DiscoveryMutex);
    return ret;
}

std::vector<WifiLAN::Player> WifiLAN::GetPlayerList()
{
    Platform::Mutex_Lock(PlayersMutex);

    std::vector<Player> ret;
    for (int i = 0; i < 16; i++)
    {
        if (Players[i].Status == Player_None) continue;

        // make a copy of the player entry, fix up the address field
        Player newp = Players[i];
        if (newp.ID == MyPlayer.ID)
        {
            newp.IsLocalPlayer = true;
            newp.Address = kLocalhost;
        }
        else
        {
            newp.IsLocalPlayer = false;
            if (newp.Status == Player_Host)
                newp.Address = HostAddress;
        }

        ret.push_back(newp);
    }

    Platform::Mutex_Unlock(PlayersMutex);
    return ret;
}


void WifiLAN::SetTuning(const Tuning& tuning)
{
    Tune = tuning;
    if (Tune.MinTimeout < 1) Tune.MinTimeout = 1;
    if (Tune.MaxTimeout < Tune.MinTimeout) Tune.MaxTimeout = Tune.MinTimeout;
    if (Tune.MaxTimeout > 1000) Tune.MaxTimeout = 1000;
    // the new values take effect on the next Process() call, on the emulator thread
}

WifiLAN::Stats WifiLAN::GetStats()
{
    Platform::Mutex_Lock(PlayersMutex);
    Stats ret = St;
    Platform::Mutex_Unlock(PlayersMutex);
    return ret;
}

void WifiLAN::ResetStats()
{
    memset(&St, 0, sizeof(St));
    SRTTus = 0;
    RTTVarus = 0;
    BackoffMs = 0;
    WindowMaxMs = 0;
    WindowStartMs = (u32)Platform::GetMSCount();
    MPRecvTimeout = std::max(RecvTimeout, Tune.MinTimeout);
    St.CurrentTimeoutMs = MPRecvTimeout;
}

// Pick how long to block waiting for MP frames.
// melonDS keeps every DS in lockstep on emulated time, so waiting longer never
// desyncs anything -- it only stalls emulation briefly. Giving up too early, on
// the other hand, makes the game see a missed reply; enough of those in a row
// and it shows "communication error". So on jittery links we want the wait to
// cover the realistic worst case, but not much more (a client that really is
// gone would otherwise slow the host down).
void WifiLAN::UpdateTimeout()
{
    int floor = std::max(RecvTimeout, Tune.MinTimeout);
    int ceil = std::max(floor, Tune.MaxTimeout);
    int timeout = floor;

    if (Tune.Adaptive)
    {
        // estimate from measured CMD->reply exchanges (RFC 6298 style)
        int fromexch = 0;
        if (SRTTus > 0)
            fromexch = (int)((SRTTus + 4 * RTTVarus) / 1000) + 10;

        // estimate from ENet's own RTT, which is available before the first exchange
        int maxping = 0;
        for (int i = 0; i < 16; i++)
        {
            if (i == MyPlayer.ID) continue;
            if (!RemotePeers[i]) continue;
            if (!(ConnectedBitmask & (1 << i))) continue;
            int ping = (int)(RemotePeers[i]->roundTripTime + 4 * RemotePeers[i]->roundTripTimeVariance);
            maxping = std::max(maxping, ping);
        }
        int fromping = maxping ? (maxping + 10) : 0;

        timeout = std::max({fromexch, fromping, BackoffMs});
        timeout = std::clamp(timeout, floor, ceil);
    }

    MPRecvTimeout = timeout;
    St.CurrentTimeoutMs = timeout;
}

void WifiLAN::RecordExchange(u64 us, bool complete)
{
    Platform::Mutex_Lock(PlayersMutex);

    if (complete)
    {
        s64 sample = (s64)us;
        if (SRTTus == 0)
        {
            SRTTus = sample;
            RTTVarus = sample / 2;
        }
        else
        {
            s64 err = sample - SRTTus;
            SRTTus += err / 8;
            RTTVarus += ((err < 0 ? -err : err) - RTTVarus) / 4;
        }
        St.Exchanges++;
        St.AvgExchangeUs = (u32)SRTTus;

        // successful rounds let a previous backoff fade out (halves in ~5 rounds)
        BackoffMs -= (BackoffMs + 7) / 8;
    }
    else
    {
        // a reply didn't make it in time: the link may be in a latency spike, so
        // wait longer next time. Kept separate from the latency estimate so that
        // timeouts can't feed back into it and pin the wait at the ceiling.
        St.ReplyTimeouts++;
        BackoffMs = std::max(BackoffMs, MPRecvTimeout + MPRecvTimeout / 2);
    }

    u32 ms = (u32)(us / 1000);
    if (ms > WindowMaxMs) WindowMaxMs = ms;

    Platform::Mutex_Unlock(PlayersMutex);

    UpdateTimeout();
}

bool WifiLAN::IsDuplicate(const ENetPacket* pkt)
{
    const MPPacketHeader* header = (const MPPacketHeader*)&pkt->data[0];

    SeenKey key;
    key.Sender = header->SenderID;
    key.Type = header->Type;
    key.Len = header->Length;
    key.Timestamp = header->Timestamp;
    key.Hash = HashBytes(&pkt->data[sizeof(MPPacketHeader)], pkt->dataLength - sizeof(MPPacketHeader));

    for (int i = 0; i < 64; i++)
    {
        const SeenKey& s = Seen[i];
        if (s.Sender == key.Sender && s.Type == key.Type && s.Len == key.Len &&
            s.Timestamp == key.Timestamp && s.Hash == key.Hash)
            return true;
    }

    Seen[SeenPos] = key;
    SeenPos = (SeenPos + 1) & 63;
    return false;
}

// Send to every connected peer. Peers that told us they discard duplicates get a
// second copy, which hides single-packet losses without waiting for a timeout.
void WifiLAN::SendToPeers(ENetPacket* pkt, u8 channel, bool redundant)
{
    for (ENetPeer* peer = Host->peers; peer < &Host->peers[Host->peerCount]; peer++)
    {
        if (peer->state != ENET_PEER_STATE_CONNECTED) continue;

        enet_peer_send(peer, channel, pkt);

        if (!redundant) continue;
        Player* player = (Player*)peer->data;
        if (!player) continue;
        if (!(PeerCaps[player->ID & 0xF] & Cap_Dedupe)) continue;

        ENetPacket* copy = enet_packet_create(pkt->data, pkt->dataLength, pkt->flags);
        enet_peer_send(peer, channel, copy);
        St.RedundantSent++;
    }

    // enet_peer_send() takes its own reference; free it if nobody did
    if (pkt->referenceCount == 0)
        enet_packet_destroy(pkt);
}

void WifiLAN::SendPlayerConnectIfBegun(ENetPeer* peer)
{
    if (!(ConnectedBitmask & (1 << MyPlayer.ID))) return;

    u8 cmd = Cmd_PlayerConnect;
    ENetPacket* pkt = enet_packet_create(&cmd, 1, ENET_PACKET_FLAG_RELIABLE);
    enet_peer_send(peer, Chan_Cmd, pkt);
}

void WifiLAN::SendCaps(ENetPeer* peer)
{
    u8 cmd[13];
    u32 flags = Cap_Dedupe;
    cmd[0] = Cmd_Caps;
    for (int i = 0; i < 4; i++)
    {
        cmd[1+i] = (u8)(kCapsMagic >> (8*i));
        cmd[5+i] = (u8)(kCapsVersion >> (8*i));
        cmd[9+i] = (u8)(flags >> (8*i));
    }
    ENetPacket* pkt = enet_packet_create(cmd, sizeof(cmd), ENET_PACKET_FLAG_RELIABLE);
    enet_peer_send(peer, Chan_Cmd, pkt);
}

void WifiLAN::HandleCaps(ENetPeer* peer, const u8* data, size_t len)
{
    if (len < 13) return;
    u32 magic = data[1] | (data[2] << 8) | (data[3] << 16) | (data[4] << 24);
    if (magic != kCapsMagic) return;
    u32 flags = data[9] | (data[10] << 8) | (data[11] << 16) | ((u32)data[12] << 24);

    Player* player = (Player*)peer->data;
    if (!player) return;

    int id = player->ID & 0xF;
    PeerCaps[id] = flags;

    Platform::Mutex_Lock(PlayersMutex);
    if (flags & Cap_Dedupe) St.RedundantPeers |= (1 << id);
    else St.RedundantPeers &= ~(1 << id);
    Platform::Mutex_Unlock(PlayersMutex);
}

// Send a discovery beacon. The limited broadcast address (255.255.255.255) is
// routed through the default interface only -- on a phone acting as a Wi-Fi
// hotspot that is usually the cellular link, so clients on the hotspot never
// see the beacon. Also send to each interface's own broadcast address.
void WifiLAN::BroadcastDiscovery(const void* data, int len)
{
    sockaddr_in_t saddr;
    memset(&saddr, 0, sizeof(saddr));
    saddr.sin_family = AF_INET;
    saddr.sin_addr.s_addr = htonl(INADDR_BROADCAST);
    saddr.sin_port = htons(kDiscoveryPort);
    sendto(DiscoverySocket, (const char*)data, len, 0, (const sockaddr_t*)&saddr, sizeof(saddr));

#ifndef __WIN32__
    struct ifaddrs* ifs = nullptr;
    if (getifaddrs(&ifs) != 0) return;

    for (struct ifaddrs* ifa = ifs; ifa; ifa = ifa->ifa_next)
    {
        if (!ifa->ifa_addr || ifa->ifa_addr->sa_family != AF_INET) continue;
        if (!(ifa->ifa_flags & IFF_UP)) continue;
        if (ifa->ifa_flags & IFF_LOOPBACK) continue;
        if (!(ifa->ifa_flags & IFF_BROADCAST)) continue;
        if (!ifa->ifa_broadaddr) continue;

        struct sockaddr_in baddr = *(struct sockaddr_in*)ifa->ifa_broadaddr;
        if (baddr.sin_addr.s_addr == htonl(INADDR_BROADCAST)) continue;
        if (baddr.sin_addr.s_addr == 0) continue;
        baddr.sin_family = AF_INET;
        baddr.sin_port = htons(kDiscoveryPort);

        sendto(DiscoverySocket, (const char*)data, len, 0, (const sockaddr_t*)&baddr, sizeof(baddr));
    }

    freeifaddrs(ifs);
#endif
}

bool WifiLAN::StartDiscovery()
{
    if (!Inited) return false;

    int res;

    DiscoverySocket = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    if (DiscoverySocket < 0)
    {
        DiscoverySocket = INVALID_SOCKET;
        return false;
    }

    sockaddr_in_t saddr;
    memset(&saddr, 0, sizeof(saddr));
    saddr.sin_family = AF_INET;
    saddr.sin_addr.s_addr = htonl(INADDR_ANY);
    saddr.sin_port = htons(kDiscoveryPort);
    res = bind(DiscoverySocket, (const sockaddr_t*)&saddr, sizeof(saddr));
    if (res < 0)
    {
        closesocket(DiscoverySocket);
        DiscoverySocket = INVALID_SOCKET;
        return false;
    }

    int opt_true = 1;
    res = setsockopt(DiscoverySocket, SOL_SOCKET, SO_BROADCAST, (const char*)&opt_true, sizeof(int));
    if (res < 0)
    {
        closesocket(DiscoverySocket);
        DiscoverySocket = INVALID_SOCKET;
        return false;
    }

    DiscoveryLastTick = (u32)Platform::GetMSCount();
    DiscoveryList.clear();

    Active = true;
    return true;
}

void WifiLAN::EndDiscovery()
{
    if (!Inited) return;

    if (DiscoverySocket != INVALID_SOCKET)
    {
        closesocket(DiscoverySocket);
        DiscoverySocket = INVALID_SOCKET;
    }

    if (!IsHost)
        Active = false;
}

bool WifiLAN::StartHost(const char* playername, int numplayers)
{
    if (!Inited) return false;
    if (numplayers > 16) return false;

    ENetAddress addr;
    addr.host = ENET_HOST_ANY;
    addr.port = kLANPort;

    Host = enet_host_create(&addr, 16, 2, 0, 0);
    if (!Host)
    {
        return false;
    }

    Platform::Mutex_Lock(PlayersMutex);

    Player* player = &Players[0];
    memset(player, 0, sizeof(Player));
    player->ID = 0;
    strncpy(player->Name, playername, 31);
    player->Status = Player_Host;
    player->Address = kLocalhost;
    NumPlayers = 1;
    MaxPlayers = numplayers;
    memcpy(&MyPlayer, player, sizeof(Player));

    Platform::Mutex_Unlock(PlayersMutex);

    HostAddress = kLocalhost;
    LastHostID = -1;
    LastHostPeer = nullptr;

    memset(PeerCaps, 0, sizeof(PeerCaps));
    memset(Seen, 0, sizeof(Seen));
    ResetStats();

    Active = true;
    IsHost = true;

    StartDiscovery();
    return true;
}

bool WifiLAN::StartClient(const char* playername, const char* host)
{
    if (!Inited) return false;

    Host = enet_host_create(nullptr, 16, 2, 0, 0);
    if (!Host)
    {
        return false;
    }

    ENetAddress addr;
    enet_address_set_host(&addr, host);
    addr.port = kLANPort;
    ENetPeer* peer = enet_host_connect(Host, &addr, 2, 0);
    if (!peer)
    {
        enet_host_destroy(Host);
        Host = nullptr;
        return false;
    }

    Platform::Mutex_Lock(PlayersMutex);

    Player* player = &MyPlayer;
    memset(player, 0, sizeof(Player));
    player->ID = 0;
    strncpy(player->Name, playername, 31);
    player->Status = Player_Connecting;

    Platform::Mutex_Unlock(PlayersMutex);

    ENetEvent event;
    int conn = 0;
    u32 starttick = (u32)Platform::GetMSCount();
    const int conntimeout = 5000;
    for (;;)
    {
        u32 curtick = (u32)Platform::GetMSCount();
        if (curtick < starttick) break;
        int timeout = conntimeout - (int)(curtick - starttick);
        if (timeout < 0) break;
        if (enet_host_service(Host, &event, timeout) > 0)
        {
            if (conn == 0 && event.type == ENET_EVENT_TYPE_CONNECT)
            {
                DisableThrottle(event.peer);
                conn = 1;
            }
            else if (conn == 1 && event.type == ENET_EVENT_TYPE_RECEIVE)
            {
                u8* data = event.packet->data;
                if (event.channelID != Chan_Cmd) continue;
                if (data[0] != Cmd_ClientInit) continue;
                if (event.packet->dataLength != 11) continue;

                u32 magic = data[1] | (data[2] << 8) | (data[3] << 16) | (data[4] << 24);
                u32 version = data[5] | (data[6] << 8) | (data[7] << 16) | (data[8] << 24);
                if (magic != kLANMagic) continue;
                if (version != kProtocolVersion) continue;
                if (data[10] > 16) continue;

                MaxPlayers = data[10];

                // send player information
                MyPlayer.ID = data[9];
                u8 cmd[9+sizeof(Player)];
                cmd[0] = Cmd_PlayerInfo;
                cmd[1] = (u8)kLANMagic;
                cmd[2] = (u8)(kLANMagic >> 8);
                cmd[3] = (u8)(kLANMagic >> 16);
                cmd[4] = (u8)(kLANMagic >> 24);
                cmd[5] = (u8)kProtocolVersion;
                cmd[6] = (u8)(kProtocolVersion >> 8);
                cmd[7] = (u8)(kProtocolVersion >> 16);
                cmd[8] = (u8)(kProtocolVersion >> 24);
                memcpy(&cmd[9], &MyPlayer, sizeof(Player));
                ENetPacket* pkt = enet_packet_create(cmd, 9+sizeof(Player), ENET_PACKET_FLAG_RELIABLE);
                enet_peer_send(event.peer, Chan_Cmd, pkt);

                conn = 2;
                break;
            }
            else if (event.type == ENET_EVENT_TYPE_DISCONNECT)
            {
                conn = 0;
                break;
            }
        }
        else
            break;
    }

    if (conn != 2)
    {
        enet_peer_reset(peer);
        enet_host_destroy(Host);
        Host = nullptr;
        return false;
    }

    HostAddress = addr.host;
    LastHostID = -1;
    LastHostPeer = nullptr;
    RemotePeers[0] = peer;
    peer->data = &Players[0];

    memset(PeerCaps, 0, sizeof(PeerCaps));
    memset(Seen, 0, sizeof(Seen));
    ResetStats();
    SendCaps(peer);

    Active = true;
    IsHost = false;
    return true;
}

void WifiLAN::EndSession()
{
    if (!Active) return;
    if (IsHost) EndDiscovery();

    Active = false;

    while (!RXQueue.empty())
    {
        ENetPacket* packet = RXQueue.front();
        RXQueue.pop();
        enet_packet_destroy(packet);
    }

    for (int i = 0; i < 16; i++)
    {
        if (i == MyPlayer.ID) continue;

        if (RemotePeers[i])
            enet_peer_disconnect(RemotePeers[i], 0);

        RemotePeers[i] = nullptr;
    }

    enet_host_destroy(Host);
    Host = nullptr;
    IsHost = false;
}


void WifiLAN::ProcessDiscovery()
{
    if (DiscoverySocket == INVALID_SOCKET)
        return;

    u32 tick = (u32)Platform::GetMSCount();
    if ((tick - DiscoveryLastTick) < 1000)
        return;

    DiscoveryLastTick = tick;

    if (IsHost)
    {
        // advertise this LAN session over the network

        DiscoveryData beacon;
        memset(&beacon, 0, sizeof(beacon));
        beacon.Magic = kDiscoveryMagic;
        beacon.Version = kProtocolVersion;
        beacon.Tick = tick;
        snprintf(beacon.SessionName, 64, "%s's game", MyPlayer.Name);
        beacon.NumPlayers = NumPlayers;
        beacon.MaxPlayers = MaxPlayers;
        beacon.Status = 0; // TODO

        BroadcastDiscovery(&beacon, sizeof(beacon));
    }
    else
    {
        Platform::Mutex_Lock(DiscoveryMutex);

        // listen for LAN sessions

        fd_set fd;
        struct timeval tv;
        for (;;)
        {
            FD_ZERO(&fd); FD_SET(DiscoverySocket, &fd);
            tv.tv_sec = 0; tv.tv_usec = 0;
            if (!select(DiscoverySocket+1, &fd, nullptr, nullptr, &tv))
                break;

            DiscoveryData beacon;
            sockaddr_in_t raddr;
            socklen_t ralen = sizeof(raddr);

            int rlen = recvfrom(DiscoverySocket, (char*)&beacon, sizeof(beacon), 0, (sockaddr_t*)&raddr, &ralen);
            if (rlen < sizeof(beacon)) continue;
            if (beacon.Magic != kDiscoveryMagic) continue;
            if (beacon.Version != kProtocolVersion) continue;
            if (beacon.MaxPlayers > 16) continue;
            if (beacon.NumPlayers > beacon.MaxPlayers) continue;

            u32 key = ntohl(raddr.sin_addr.s_addr);

            if (DiscoveryList.find(key) != DiscoveryList.end())
            {
                if (beacon.Tick <= DiscoveryList[key].Tick)
                    continue;
            }

            beacon.Magic = tick;
            beacon.SessionName[63] = '\0';
            DiscoveryList[key] = beacon;
        }

        // cleanup: remove hosts that haven't given a sign of life in the last 5 seconds

        std::vector<u32> deletelist;

        for (const auto& [key, data] : DiscoveryList)
        {
            u32 age = tick - data.Magic;
            if (age < 5000) continue;

            deletelist.push_back(key);
        }

        for (const auto& key : deletelist)
        {
            DiscoveryList.erase(key);
        }

        Platform::Mutex_Unlock(DiscoveryMutex);
    }
}

void WifiLAN::HostUpdatePlayerList()
{
    u8 cmd[2+sizeof(Players)];
    cmd[0] = Cmd_PlayerList;
    cmd[1] = (u8)NumPlayers;
    memcpy(&cmd[2], Players, sizeof(Players));
    ENetPacket* pkt = enet_packet_create(cmd, 2+sizeof(Players), ENET_PACKET_FLAG_RELIABLE);
    enet_host_broadcast(Host, Chan_Cmd, pkt);
}

void WifiLAN::ClientUpdatePlayerList()
{
}

void WifiLAN::ProcessHostEvent(ENetEvent& event)
{
    switch (event.type)
    {
    case ENET_EVENT_TYPE_CONNECT:
        {
            DisableThrottle(event.peer);

            if ((NumPlayers >= MaxPlayers) || (NumPlayers >= 16))
            {
                // game is full, reject connection
                enet_peer_disconnect(event.peer, 0);
                break;
            }

            // client connected; assign player number

            int id;
            for (id = 0; id < 16; id++)
            {
                if (id >= NumPlayers) break;
                if (Players[id].Status == Player_None) break;
            }

            if (id < 16)
            {
                u8 cmd[11];
                cmd[0] = Cmd_ClientInit;
                cmd[1] = (u8)kLANMagic;
                cmd[2] = (u8)(kLANMagic >> 8);
                cmd[3] = (u8)(kLANMagic >> 16);
                cmd[4] = (u8)(kLANMagic >> 24);
                cmd[5] = (u8)kProtocolVersion;
                cmd[6] = (u8)(kProtocolVersion >> 8);
                cmd[7] = (u8)(kProtocolVersion >> 16);
                cmd[8] = (u8)(kProtocolVersion >> 24);
                cmd[9] = (u8)id;
                cmd[10] = MaxPlayers;
                ENetPacket* pkt = enet_packet_create(cmd, 11, ENET_PACKET_FLAG_RELIABLE);
                enet_peer_send(event.peer, Chan_Cmd, pkt);

                Platform::Mutex_Lock(PlayersMutex);

                Players[id].ID = id;
                Players[id].Status = Player_Connecting;
                Players[id].Address = event.peer->address.host;
                event.peer->data = &Players[id];
                NumPlayers++;

                Platform::Mutex_Unlock(PlayersMutex);

                RemotePeers[id] = event.peer;
                PeerCaps[id] = 0;
                SendCaps(event.peer);
            }
            else
            {
                // ???
                enet_peer_disconnect(event.peer, 0);
            }
        }
        break;

    case ENET_EVENT_TYPE_DISCONNECT:
        {
            Player* player = (Player*)event.peer->data;
            if (!player) break;

            ConnectedBitmask &= ~(1 << player->ID);

            int id = player->ID;
            RemotePeers[id] = nullptr;
            PeerCaps[id] = 0;

            player->ID = 0;
            player->Status = Player_None;
            NumPlayers--;

            // broadcast updated player list
            HostUpdatePlayerList();
        }
        break;

    case ENET_EVENT_TYPE_RECEIVE:
        {
            if (event.packet->dataLength < 1) break;

            u8* data = (u8*)event.packet->data;
            switch (data[0])
            {
            case Cmd_PlayerInfo: // client sending player info
                {
                    if (event.packet->dataLength != (9+sizeof(Player))) break;

                    u32 magic = data[1] | (data[2] << 8) | (data[3] << 16) | (data[4] << 24);
                    u32 version = data[5] | (data[6] << 8) | (data[7] << 16) | (data[8] << 24);
                    if ((magic != kLANMagic) || (version != kProtocolVersion))
                    {
                        enet_peer_disconnect(event.peer, 0);
                        break;
                    }

                    Player player;
                    memcpy(&player, &data[9], sizeof(Player));
                    player.Name[31] = '\0';

                    Player* hostside = (Player*)event.peer->data;
                    if (player.ID != hostside->ID)
                    {
                        enet_peer_disconnect(event.peer, 0);
                        break;
                    }

                    Platform::Mutex_Lock(PlayersMutex);

                    player.Status = Player_Client;
                    player.Address = event.peer->address.host;
                    memcpy(hostside, &player, sizeof(Player));

                    Platform::Mutex_Unlock(PlayersMutex);

                    // broadcast updated player list
                    HostUpdatePlayerList();

                    // if our game is already running, tell the newcomer; the original
                    // Cmd_PlayerConnect broadcast happened before they were connected
                    SendPlayerConnectIfBegun(event.peer);
                }
                break;

            case Cmd_PlayerConnect: // player connected
                {
                    if (event.packet->dataLength != 1) break;
                    Player* player = (Player*)event.peer->data;
                    if (!player) break;

                    ConnectedBitmask |= (1 << player->ID);
                }
                break;

            case Cmd_PlayerDisconnect: // player disconnected
                {
                    if (event.packet->dataLength != 1) break;
                    Player* player = (Player*)event.peer->data;
                    if (!player) break;

                    ConnectedBitmask &= ~(1 << player->ID);
                }
                break;

            case Cmd_Caps: // peer advertising optional capabilities
                HandleCaps(event.peer, data, event.packet->dataLength);
                break;
            }

            enet_packet_destroy(event.packet);
        }
        break;
    case ENET_EVENT_TYPE_NONE:
        break;
    }
}

void WifiLAN::ProcessClientEvent(ENetEvent& event)
{
    switch (event.type)
    {
    case ENET_EVENT_TYPE_CONNECT:
        {
            // another client is establishing a direct connection to us
            DisableThrottle(event.peer);

            int playerid = -1;
            for (int i = 0; i < 16; i++)
            {
                Player* player = &Players[i];
                if (i == MyPlayer.ID) continue;
                if (player->Status != Player_Client) continue;

                if (player->Address == event.peer->address.host)
                {
                    playerid = i;
                    break;
                }
            }

            if (playerid < 0)
            {
                enet_peer_disconnect(event.peer, 0);
                break;
            }

            RemotePeers[playerid] = event.peer;
            event.peer->data = &Players[playerid];
            PeerCaps[playerid] = 0;
            SendCaps(event.peer);
            SendPlayerConnectIfBegun(event.peer);
        }
        break;

    case ENET_EVENT_TYPE_DISCONNECT:
        {
            Player* player = (Player*)event.peer->data;
            if (!player) break;

            ConnectedBitmask &= ~(1 << player->ID);

            int id = player->ID;
            RemotePeers[id] = nullptr;
            PeerCaps[id] = 0;

            Platform::Mutex_Lock(PlayersMutex);
            player->Status = Player_Disconnected;
            Platform::Mutex_Unlock(PlayersMutex);

            ClientUpdatePlayerList();
        }
        break;

    case ENET_EVENT_TYPE_RECEIVE:
        {
            if (event.packet->dataLength < 1) break;

            u8* data = (u8*)event.packet->data;
            switch (data[0])
            {
            case Cmd_PlayerList: // host sending player list
                {
                    if (event.packet->dataLength != (2+sizeof(Players))) break;
                    if (data[1] > 16) break;

                    Platform::Mutex_Lock(PlayersMutex);

                    NumPlayers = data[1];
                    memcpy(Players, &data[2], sizeof(Players));
                    for (int i = 0; i < 16; i++)
                    {
                        Players[i].Name[31] = '\0';
                    }

                    Platform::Mutex_Unlock(PlayersMutex);

                    // establish connections to any new clients
                    for (int i = 0; i < 16; i++)
                    {
                        Player* player = &Players[i];
                        if (i == MyPlayer.ID) continue;
                        if (player->Status != Player_Client) continue;

                        if (!RemotePeers[i])
                        {
                            ENetAddress peeraddr;
                            peeraddr.host = player->Address;
                            peeraddr.port = kLANPort;
                            ENetPeer* peer = enet_host_connect(Host, &peeraddr, 2, 0);
                            if (!peer)
                            {
                                // TODO deal with this
                                continue;
                            }
                        }
                    }
                }
                break;

            case Cmd_PlayerConnect: // player connected
                {
                    if (event.packet->dataLength != 1) break;
                    Player* player = (Player*)event.peer->data;
                    if (!player) break;

                    ConnectedBitmask |= (1 << player->ID);
                }
                break;

            case Cmd_PlayerDisconnect: // player disconnected
                {
                    if (event.packet->dataLength != 1) break;
                    Player* player = (Player*)event.peer->data;
                    if (!player) break;

                    ConnectedBitmask &= ~(1 << player->ID);
                }
                break;

            case Cmd_Caps: // peer advertising optional capabilities
                HandleCaps(event.peer, data, event.packet->dataLength);
                break;
            }

            enet_packet_destroy(event.packet);
        }
        break;
    case ENET_EVENT_TYPE_NONE:
        break;
    }
}

void WifiLAN::ProcessEvent(ENetEvent& event)
{
    if (IsHost)
        ProcessHostEvent(event);
    else
        ProcessClientEvent(event);
}

// 0 = per-frame processing of events and eventual misc. frame
// 1 = checking if a misc. frame has arrived
// 2 = waiting for a MP frame
// Validate an ENet event; MP frames are queued for the core, anything else goes
// to the session logic. Returns true if a frame was queued.
bool WifiLAN::HandleIncoming(ENetEvent& event)
{
    if (!(event.type == ENET_EVENT_TYPE_RECEIVE && event.channelID == Chan_MP))
    {
        ProcessEvent(event);
        return false;
    }

    MPPacketHeader* header = (MPPacketHeader*)&event.packet->data[0];

    bool good = true;
    if (event.packet->dataLength < sizeof(MPPacketHeader))
        good = false;
    else if (header->Magic != 0x4946494E)
        good = false;
    else if (header->SenderID == MyPlayer.ID)
        good = false;
    // only MP frames are ever sent twice (see SendPacketGeneric), so only they
    // need filtering; regular frames always pass through untouched
    else if ((header->Type & 0xFFFF) != 0 && IsDuplicate(event.packet))
    {
        good = false;
        St.DuplicateDrops++;
    }

    if (!good)
    {
        enet_packet_destroy(event.packet);
        return false;
    }

    // a reply to a handshake frame we're waiting on?
    if (AwaitReply)
    {
        if (header->Type != 0)
            ReplyArrived = true;
        else if (header->Length >= 12+24 &&
                 memcmp(&event.packet->data[sizeof(MPPacketHeader) + 12 + 4], AwaitMAC, 6) == 0)
            ReplyArrived = true;
    }

    // mark this packet with the time it was received
    header->Magic = (u32)Platform::GetMSCount();

    event.packet->userData = event.peer;
    RXQueue.push(event.packet);
    return true;
}

void WifiLAN::ProcessLAN(int type, int timeoutms)
{
    if (!Host) return;

    u32 time_last = (u32)Platform::GetMSCount();

    // how long a received frame may sit in the queue before it's considered stale;
    // this used to be a fixed 16ms (one frame), which is shorter than a single
    // Wi-Fi latency spike, so it scales with the receive wait now
    u32 stalewindow = (u32)std::max(16, MPRecvTimeout);

    // see if we have queued packets already, get rid of the stale ones
    // any incoming packet should be consumed by the core quickly, so if
    // they've been sitting in the queue for more than one frame's time,
    // we can assume they're stale
    while (!RXQueue.empty())
    {
        ENetPacket* enetpacket = RXQueue.front();
        MPPacketHeader* header = (MPPacketHeader*)&enetpacket->data[0];
        u32 packettime = header->Magic;

        if ((s32)(time_last - packettime) < 0 || (time_last - packettime) > stalewindow)
        {
            RXQueue.pop();
            enet_packet_destroy(enetpacket);
            St.StaleDrops++;
        }
        else
        {
            // we got a packet, depending on what the caller wants we might be able to return now
            if (type == 2) return;
            if (type == 1)
            {
                // if looking for a misc. frame, we shouldn't be receiving a MP frame
                if (header->Type == 0)
                    return;

                RXQueue.pop();
                enet_packet_destroy(enetpacket);
            }

            break;
        }
    }

    int timeout = (type == 2) ? ((timeoutms >= 0) ? timeoutms : MPRecvTimeout) : 0;
    time_last = (u32)Platform::GetMSCount();

    ENetEvent event;
    while (enet_host_service(Host, &event, timeout) > 0)
    {
        if (HandleIncoming(event))
        {
            // return now -- if we are receiving MP frames, if we keep going
            // we'll consume too many even if we have no timeout set
            return;
        }

        if (type == 2)
        {
            u32 time = (u32)Platform::GetMSCount();
            if (time < time_last) return;
            timeout -= (int)(time - time_last);
            if (timeout <= 0) return;
            time_last = time;
        }
    }
}

void WifiLAN::Process()
{
    if (!Active) return;

    ProcessDiscovery();
    ProcessLAN(0);

    u32 now = (u32)Platform::GetMSCount();
    Platform::Mutex_Lock(PlayersMutex);
    for (int i = 0; i < 16; i++)
        St.PeerPing[i] = (RemotePeers[i] && i != MyPlayer.ID) ? RemotePeers[i]->roundTripTime : 0;
    if ((now - WindowStartMs) >= 1000)
    {
        St.MaxExchangeMs = WindowMaxMs;
        WindowMaxMs = 0;
        WindowStartMs = now;
    }
    Platform::Mutex_Unlock(PlayersMutex);
    UpdateTimeout();

    FrameCount++;
    if (FrameCount >= 60)
    {
        FrameCount = 0;

        Platform::Mutex_Lock(PlayersMutex);

        for (int i = 0; i < 16; i++)
        {
            if (Players[i].Status == Player_None) continue;
            if (i == MyPlayer.ID) continue;
            if (!RemotePeers[i]) continue;

            Players[i].Ping = RemotePeers[i]->roundTripTime;
        }

        Platform::Mutex_Unlock(PlayersMutex);
    }
}


void WifiLAN::Begin(int inst)
{
    if (!Host) return;

    ConnectedBitmask |= (1 << MyPlayer.ID);
    LastHostID = -1;
    LastHostPeer = nullptr;

    u8 cmd = Cmd_PlayerConnect;
    ENetPacket* pkt = enet_packet_create(&cmd, 1, ENET_PACKET_FLAG_RELIABLE);
    enet_host_broadcast(Host, Chan_Cmd, pkt);
}

void WifiLAN::End(int inst)
{
    if (!Host) return;

    ConnectedBitmask &= ~(1 << MyPlayer.ID);

    u8 cmd = Cmd_PlayerDisconnect;
    ENetPacket* pkt = enet_packet_create(&cmd, 1, ENET_PACKET_FLAG_RELIABLE);
    enet_host_broadcast(Host, Chan_Cmd, pkt);
}


int WifiLAN::SendPacketGeneric(u32 type, u8* packet, int len, u64 timestamp)
{
    if (!Host) return 0;

    // TODO make the reliable part optional?
    //u32 flags = ENET_PACKET_FLAG_RELIABLE;
    u32 flags = ENET_PACKET_FLAG_UNSEQUENCED;

    ENetPacket* enetpacket = enet_packet_create(nullptr, sizeof(MPPacketHeader)+len, flags);

    MPPacketHeader pktheader;
    pktheader.Magic = 0x4946494E;
    pktheader.SenderID = MyPlayer.ID;
    pktheader.Type = type;
    pktheader.Length = len;
    pktheader.Timestamp = timestamp;
    memcpy(&enetpacket->data[0], &pktheader, sizeof(MPPacketHeader));
    if (len)
        memcpy(&enetpacket->data[sizeof(MPPacketHeader)], packet, len);

    // Regular (non-MP) frames aren't exchanged in lockstep: both consoles keep
    // running while they travel. That's fine for beacons, but the joining
    // handshake (authentication / association) has tight reply deadlines in
    // emulated time, so over Wi-Fi the reply arrives "late" and the DS reports a
    // communication error. After sending a frame to a specific console, pause on
    // the next receive until that console answers, so the reply looks instant.
    if (type == 0 && Tune.HandshakeLockstep && len >= 12+24)
    {
        u16 framectl = packet[12] | (packet[13] << 8);
        const u8* dest = &packet[12 + 4];
        bool unicast = !(dest[0] & 0x01);
        bool control = (framectl & 0x000C) == 0x0004;
        if (unicast && !control)
        {
            AwaitReply = true;
            ReplyArrived = false;
            AwaitStartMs = (u32)Platform::GetMSCount();
            memcpy(AwaitMAC, &packet[12 + 10], 6); // our own address (addr2)
        }
    }

    // MP frames (CMD, reply, ack) are the latency-critical ones worth duplicating;
    // regular frames (beacons etc) are periodic anyway
    bool redundant = Tune.Redundancy && ((type & 0xFFFF) != 0);

    if (((type & 0xFFFF) == 2) && LastHostPeer)
    {
        enet_peer_send(LastHostPeer, Chan_MP, enetpacket);

        Player* hostplayer = (Player*)LastHostPeer->data;
        if (redundant && hostplayer && (PeerCaps[hostplayer->ID & 0xF] & Cap_Dedupe))
        {
            ENetPacket* copy = enet_packet_create(enetpacket->data, enetpacket->dataLength, flags);
            enet_peer_send(LastHostPeer, Chan_MP, copy);
            St.RedundantSent++;
        }
    }
    else
        SendToPeers(enetpacket, Chan_MP, redundant);
    enet_host_flush(Host);

    return len;
}

int WifiLAN::RecvPacketGeneric(u8* packet, bool block, u64* timestamp)
{
    if (!Host) return 0;

    ProcessLAN(block ? 2 : 1);
    if (RXQueue.empty()) return 0;

    ENetPacket* enetpacket = RXQueue.front();
    RXQueue.pop();
    MPPacketHeader* header = (MPPacketHeader*)&enetpacket->data[0];

    u32 len = header->Length;
    if (len)
    {
        if (len > 2048) len = 2048;

        memcpy(packet, &enetpacket->data[sizeof(MPPacketHeader)], len);

        if (header->Type == 1)
        {
            LastHostID = header->SenderID;
            LastHostPeer = (ENetPeer*)enetpacket->userData;
        }
    }

    if (timestamp) *timestamp = header->Timestamp;
    enet_packet_destroy(enetpacket);
    return len;
}


int WifiLAN::SendPacket(int inst, u8* packet, int len, u64 timestamp)
{
    return SendPacketGeneric(0, packet, len, timestamp);
}

int WifiLAN::RecvPacket(int inst, u8* packet, u64* timestamp)
{
    if (AwaitReply && Host)
    {
        int budget = std::clamp(MPRecvTimeout * 2, 40, std::max(40, Tune.MaxTimeout));
        u32 start = AwaitStartMs;

        while (!ReplyArrived)
        {
            int elapsed = (int)((u32)Platform::GetMSCount() - start);
            if (elapsed >= budget) break;

            ENetEvent event;
            if (enet_host_service(Host, &event, budget - elapsed) <= 0)
                break;
            HandleIncoming(event);
        }

        u32 waited = (u32)Platform::GetMSCount() - start;
        Platform::Mutex_Lock(PlayersMutex);
        St.HandshakeWaits++;
        if (!ReplyArrived) St.HandshakeTimeouts++;
        St.LastHandshakeMs = waited;
        if (waited > St.MaxHandshakeMs) St.MaxHandshakeMs = waited;
        Platform::Mutex_Unlock(PlayersMutex);

        Platform::Log(Platform::LogLevel::Info, "LAN: handshake reply %s after %ums\n",
                      ReplyArrived ? "arrived" : "TIMED OUT", waited);

        AwaitReply = false;
        ReplyArrived = false;
    }

    return RecvPacketGeneric(packet, false, timestamp);
}


int WifiLAN::SendCmd(int inst, u8* packet, int len, u64 timestamp)
{
    return SendPacketGeneric(1, packet, len, timestamp);
}

int WifiLAN::SendReply(int inst, u8* packet, int len, u64 timestamp, u16 aid)
{
    return SendPacketGeneric(2 | (aid<<16), packet, len, timestamp);
}

int WifiLAN::SendAck(int inst, u8* packet, int len, u64 timestamp)
{
    return SendPacketGeneric(3, packet, len, timestamp);
}

int WifiLAN::RecvHostPacket(int inst, u8* packet, u64* timestamp)
{
    if (LastHostID != -1)
    {
        // check if the host is still connected

        if (!(ConnectedBitmask & (1<<LastHostID)))
        {
            Platform::Log(Platform::LogLevel::Warn, "LAN: host (player %d) not ready for MP frames; mask=%04X\n",
                          LastHostID, ConnectedBitmask);
            return -1;
        }
    }

    return RecvPacketGeneric(packet, true, timestamp);
}

u16 WifiLAN::RecvReplies(int inst, u8* packets, u64 timestamp, u16 aidmask)
{
    if (!Host) return 0;

    u16 ret = 0;
    u16 myinstmask = 1 << MyPlayer.ID;

    if ((myinstmask & ConnectedBitmask) == ConnectedBitmask)
        return 0;

    // the wait is a deadline for the whole exchange, not per packet, so that
    // N clients don't turn into N times the timeout
    u64 start = MonotonicUS();
    int timeout = MPRecvTimeout;

    for (;;)
    {
        int elapsed = (int)((MonotonicUS() - start) / 1000);
        ProcessLAN(2, std::max(0, timeout - elapsed));
        if (RXQueue.empty())
        {
            // no more replies available
            RecordExchange(MonotonicUS() - start, false);
            return ret;
        }

        ENetPacket* enetpacket = RXQueue.front();
        RXQueue.pop();
        MPPacketHeader* header = (MPPacketHeader*)&enetpacket->data[0];
        bool good = true;
        if ((header->Type & 0xFFFF) != 2)
            good = false;
        else if (header->Timestamp < (timestamp - 32))
            good = false;

        if (good)
        {
            u32 len = header->Length;
            if (len)
            {
                if (len > 1024) len = 1024;

                u32 aid = header->Type >> 16;
                memcpy(&packets[(aid-1)*1024], &enetpacket->data[sizeof(MPPacketHeader)], len);

                ret |= (1<<aid);
            }

            myinstmask |= (1<<header->SenderID);
            if (((myinstmask & ConnectedBitmask) == ConnectedBitmask) ||
                ((ret & aidmask) == aidmask))
            {
                // all the clients have sent their reply
                enet_packet_destroy(enetpacket);
                RecordExchange(MonotonicUS() - start, true);
                return ret;
            }
        }

        enet_packet_destroy(enetpacket);
    }
}

}
