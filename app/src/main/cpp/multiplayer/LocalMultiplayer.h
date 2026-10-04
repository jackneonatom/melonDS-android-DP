#ifndef LOCALMULTIPLAYER_H
#define LOCALMULTIPLAYER_H

#include <array>
#include <mutex>
#include <string>
#include <vector>
#include "types.h"
#include "WifiLAN.h"

// Local wireless multiplayer between separate devices (Android <-> Android or
// Android <-> PC melonDS) over Wi-Fi or a phone hotspot.
//
// The emulator core talks to the network only through Platform::MP_* calls.
// While a session is active they are routed to a WifiLAN instance; otherwise to
// the core's default (dummy) interface, which keeps single-player unchanged.
namespace MelonDSAndroid::LocalMultiplayer
{
    struct DiscoveredSession
    {
        std::string address;
        std::string name;
        int numPlayers;
        int maxPlayers;
    };

    // Lock that serialises all access to the active multiplayer interface. The
    // emulator thread holds it during each MP_* call; lobby operations take it too.
    std::unique_lock<std::mutex> lock();

    // Interface the emulator should use right now. Call with lock() held.
    melonDS::MPInterface& mp();

    // Emulator hooks
    void onMPBegin(int inst);
    void onMPEnd(int inst);
    void onEmulatorFrame();

    bool isSessionActive();
    bool isHost();

    // Last three bytes of the console MAC address to use while a session is
    // active. Random per app process, so two devices running the same firmware
    // dump still look like two different consoles.
    std::array<melonDS::u8, 3> sessionMacSuffix();

    // Lobby
    bool startDiscovery();
    void stopDiscovery();
    bool startHost(const std::string& playerName, int maxPlayers);
    bool startClient(const std::string& playerName, const std::string& hostAddress);
    void endSession();

    std::vector<DiscoveredSession> getDiscoveredSessions();
    std::vector<melonDS::WifiLAN::Player> getPlayers();
    melonDS::WifiLAN::Stats getStats();
    void setTuning(const melonDS::WifiLAN::Tuning& tuning);

    // Diagnostics: recent multiplayer / Wi-Fi log lines plus a state snapshot,
    // so a failed session can be shared and debugged without adb.
    void appendLog(int level, const char* message);
    std::string getDiagnostics();
}

#endif // LOCALMULTIPLAYER_H
