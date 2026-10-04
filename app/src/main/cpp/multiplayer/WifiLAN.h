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

#ifndef WIFILAN_H
#define WIFILAN_H

#include <string>
#include <vector>
#include <map>
#include <queue>

#include <enet/enet.h>

#ifndef socket_t
    #ifdef __WIN32__
        #include <winsock2.h>
        #define socket_t    SOCKET
    #else
        #define socket_t    int
    #endif
#endif

#include "types.h"
#include "Platform.h"
#include "MPInterface.h"

namespace melonDS
{

class WifiLAN : public MPInterface
{
public:
    WifiLAN() noexcept;
    WifiLAN(const WifiLAN&) = delete;
    WifiLAN& operator=(const WifiLAN&) = delete;
    WifiLAN(WifiLAN&& other) = delete;
    WifiLAN& operator=(WifiLAN&& other) = delete;
    ~WifiLAN() noexcept;

    enum PlayerStatus
    {
        Player_None = 0,        // no player in this entry
        Player_Client,          // game client
        Player_Host,            // game host
        Player_Connecting,      // player still connecting
        Player_Disconnected,    // player disconnected
    };

    struct Player
    {
        int ID;
        char Name[32];
        PlayerStatus Status;
        u32 Address;

        bool IsLocalPlayer;
        u32 Ping;
    };

    struct DiscoveryData
    {
        u32 Magic;
        u32 Version;
        u32 Tick;
        char SessionName[64];
        u8 NumPlayers;
        u8 MaxPlayers;
        u8 Status; // 0=idle 1=playing
    };

    bool StartDiscovery();
    void EndDiscovery();
    bool StartHost(const char* player, int numplayers);
    bool StartClient(const char* player, const char* host);
    void EndSession();

    std::map<u32, DiscoveryData> GetDiscoveryList();
    std::vector<Player> GetPlayerList();
    int GetNumPlayers() { return NumPlayers; }
    int GetMaxPlayers() { return MaxPlayers; }

    // --- Wi-Fi / hotspot tuning ---------------------------------------------
    // The DS wireless protocol expects client replies within a few hundred
    // microseconds. melonDS runs host and clients in lockstep on emulated time,
    // so network latency turns into brief stalls instead of failures -- as long
    // as we wait long enough. On Wi-Fi (especially a phone hotspot) latency
    // spikes of 30-150ms are common, which the old fixed 25ms wait could not
    // absorb. These settings let the wait adapt to the measured exchange time.
    struct Tuning
    {
        bool Adaptive = true;       // derive the wait from measured exchange times
        int MinTimeout = 25;        // ms, lower bound (also used when not adaptive)
        int MaxTimeout = 300;       // ms, upper bound for the adaptive wait
        bool Redundancy = true;     // send MP frames twice to peers that support dedupe
        bool HandshakeLockstep = true; // pause until a console answers our direct frames
    };

    struct Stats
    {
        u32 Exchanges;          // host: CMD->reply rounds completed with all replies
        u32 ReplyTimeouts;      // host: rounds that ended with missing replies
        u32 StaleDrops;         // packets discarded for being too old
        u32 DuplicateDrops;     // redundant copies discarded
        u32 RedundantSent;      // extra copies sent
        u32 AvgExchangeUs;      // smoothed host CMD->all-replies time
        u32 MaxExchangeMs;      // worst round in the last second
        u32 CurrentTimeoutMs;   // wait currently in effect
        u32 PeerPing[16];       // ENet RTT per player slot, ms
        u16 RedundantPeers;     // bitmask of player slots that negotiated dedupe
        u32 HandshakeWaits;     // pauses waiting for a reply to a direct frame
        u32 HandshakeTimeouts;  // ...that ended without a reply
        u32 LastHandshakeMs;
        u32 MaxHandshakeMs;
    };

    void SetTuning(const Tuning& tuning);
    Tuning GetTuning() const { return Tune; }
    Stats GetStats();
    void ResetStats();

    void Process() override;

    void Begin(int inst) override;
    void End(int inst) override;

    int SendPacket(int inst, u8* data, int len, u64 timestamp) override;
    int RecvPacket(int inst, u8* data, u64* timestamp) override;
    int SendCmd(int inst, u8* data, int len, u64 timestamp) override;
    int SendReply(int inst, u8* data, int len, u64 timestamp, u16 aid) override;
    int SendAck(int inst, u8* data, int len, u64 timestamp) override;
    int RecvHostPacket(int inst, u8* data, u64* timestamp) override;
    u16 RecvReplies(int inst, u8* data, u64 timestamp, u16 aidmask) override;

private:
    bool Inited;
    bool Active;
    bool IsHost;

    ENetHost* Host;
    ENetPeer* RemotePeers[16];

    socket_t DiscoverySocket;
    u32 DiscoveryLastTick;
    std::map<u32, DiscoveryData> DiscoveryList;
    Platform::Mutex* DiscoveryMutex;

    Player Players[16];
    int NumPlayers;
    int MaxPlayers;
    Platform::Mutex* PlayersMutex;

    Player MyPlayer;
    u32 HostAddress;

    u16 ConnectedBitmask;

    int MPRecvTimeout;
    int LastHostID;
    ENetPeer* LastHostPeer;
    std::queue<ENetPacket*> RXQueue;

    u32 FrameCount;

    Tuning Tune;
    Stats St;
    u32 PeerCaps[16];           // capability flags received from each player slot
    s64 SRTTus, RTTVarus;       // smoothed exchange time / variance (Jacobson/Karels)
    int BackoffMs;              // raised when replies time out, decays on success
    u32 WindowMaxMs, WindowStartMs;
    Stats LastSummary;          // for the periodic diagnostics line
    u32 LastSummaryMs;

    struct SeenKey { u32 Sender, Type, Len, Hash; u64 Timestamp; };
    SeenKey Seen[64];
    int SeenPos;

    void SendCaps(ENetPeer* peer);
    void SendPlayerConnectIfBegun(ENetPeer* peer);
    void HandleCaps(ENetPeer* peer, const u8* data, size_t len);
    void UpdateTimeout();
    void RecordExchange(u64 us, bool complete);
    bool IsDuplicate(const ENetPacket* pkt);
    bool HandleIncoming(ENetEvent& event);

    bool AwaitReply;            // sent a direct frame, waiting for the answer
    bool ReplyArrived;
    u32 AwaitStartMs;
    u8 AwaitMAC[6];
    void SendToPeers(ENetPacket* pkt, u8 channel, bool redundant);
    void BroadcastDiscovery(const void* data, int len);

    void ProcessDiscovery();

    void HostUpdatePlayerList();
    void ClientUpdatePlayerList();

    void ProcessHostEvent(ENetEvent& event);
    void ProcessClientEvent(ENetEvent& event);
    void ProcessEvent(ENetEvent& event);
    void ProcessLAN(int type, int timeoutms = -1);

    int SendPacketGeneric(u32 type, u8* packet, int len, u64 timestamp);
    int RecvPacketGeneric(u8* packet, bool block, u64* timestamp);
};

}

#endif // WIFILAN_H
