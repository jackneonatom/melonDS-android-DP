#include "LocalMultiplayer.h"

#include <atomic>
#include <cstring>
#include <chrono>
#include <memory>
#include <random>
#include <thread>
#include <arpa/inet.h>
#include <android/log.h>

#include "MPInterface.h"

#define LOG_TAG "melonDS-LocalMP"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

using namespace melonDS;

namespace MelonDSAndroid::LocalMultiplayer
{
    namespace
    {
        std::mutex mpMutex;
        // Created on first use and never destroyed: lobby queries (player list,
        // stats) read it from the UI thread without taking mpMutex, relying on its
        // internal mutexes, so the object itself must outlive every caller.
        std::atomic<WifiLAN*> lan { nullptr };

        std::atomic<bool> sessionActive { false };
        std::atomic<bool> discovering { false };
        std::atomic<bool> hosting { false };

        // emulator MP state, so a session started mid-game still gets Begin()
        bool emuBegun = false;
        int emuInstance = 0;

        // keeps ENet serviced while the emulator isn't running frames (lobby,
        // pause menu, loading); otherwise peers time out after a few seconds
        // detached; a generation number tells a stopped pump to exit even if a new
        // one is started before it wakes up
        std::atomic<bool> pumpRunning { false };
        std::atomic<unsigned> pumpGeneration { 0 };
        std::atomic<long long> lastEmuFrameMs { 0 };

        std::array<u8, 3> macSuffix;
        std::once_flag macOnce;

        long long nowMs()
        {
            using namespace std::chrono;
            return duration_cast<milliseconds>(steady_clock::now().time_since_epoch()).count();
        }

        WifiLAN& getLan()
        {
            WifiLAN* l = lan.load();
            if (!l)
            {
                l = new WifiLAN();
                lan.store(l);
            }
            return *l;
        }

        void pumpLoop(unsigned generation)
        {
            while (pumpRunning.load() && pumpGeneration.load() == generation)
            {
                if (nowMs() - lastEmuFrameMs.load() > 100)
                {
                    std::lock_guard<std::mutex> guard(mpMutex);
                    WifiLAN* l = lan.load();
                    if (l && (sessionActive.load() || discovering.load()))
                        l->Process();
                }
                std::this_thread::sleep_for(std::chrono::milliseconds(8));
            }
        }

        void ensurePump()
        {
            if (pumpRunning.load())
                return;
            pumpRunning = true;
            unsigned generation = ++pumpGeneration;
            std::thread(pumpLoop, generation).detach();
        }

        void stopPumpIfIdle()
        {
            if (sessionActive.load() || discovering.load())
                return;
            pumpRunning = false;
        }
    }

    std::unique_lock<std::mutex> lock()
    {
        return std::unique_lock<std::mutex>(mpMutex);
    }

    MPInterface& mp()
    {
        WifiLAN* l = lan.load();
        if (sessionActive.load() && l)
            return *l;
        return MPInterface::Get();
    }

    void onMPBegin(int inst)
    {
        emuBegun = true;
        emuInstance = inst;
        mp().Begin(inst);
    }

    void onMPEnd(int inst)
    {
        emuBegun = false;
        mp().End(inst);
    }

    void onEmulatorFrame()
    {
        lastEmuFrameMs = nowMs();
        auto guard = lock();
        mp().Process();
    }

    bool isSessionActive()
    {
        return sessionActive.load();
    }

    bool isHost()
    {
        return sessionActive.load() && hosting.load();
    }

    std::array<u8, 3> sessionMacSuffix()
    {
        std::call_once(macOnce, []() {
            std::random_device rd;
            std::mt19937 rng(rd() ^ (unsigned)nowMs());
            for (auto& b : macSuffix)
                b = (u8)(rng() & 0xFF);
        });
        return macSuffix;
    }

    bool startDiscovery()
    {
        auto guard = lock();
        if (sessionActive.load())
            return false;
        if (discovering.load())
            return true;

        bool ok = getLan().StartDiscovery();
        discovering = ok;
        if (ok)
            ensurePump();
        LOGI("discovery %s", ok ? "started" : "failed to start");
        return ok;
    }

    void stopDiscovery()
    {
        auto guard = lock();
        if (!discovering.load())
            return;
        if (WifiLAN* l = lan.load())
            l->EndDiscovery();
        discovering = false;
        stopPumpIfIdle();
    }

    bool startHost(const std::string& playerName, int maxPlayers)
    {
        auto guard = lock();
        if (sessionActive.load())
            return false;

        WifiLAN& l = getLan();
        if (discovering.load())
        {
            l.EndDiscovery();
            discovering = false;
        }

        if (!l.StartHost(playerName.c_str(), maxPlayers))
        {
            LOGI("failed to host (is port 7064 in use?)");
            return false;
        }

        hosting = true;
        sessionActive = true;
        if (emuBegun)
            l.Begin(emuInstance);
        ensurePump();
        LOGI("hosting session for %d players", maxPlayers);
        return true;
    }

    bool startClient(const std::string& playerName, const std::string& hostAddress)
    {
        auto guard = lock();
        if (sessionActive.load())
            return false;

        WifiLAN& l = getLan();
        if (discovering.load())
        {
            l.EndDiscovery();
            discovering = false;
        }

        // blocks for up to 5 seconds while connecting
        if (!l.StartClient(playerName.c_str(), hostAddress.c_str()))
        {
            LOGI("failed to join %s", hostAddress.c_str());
            stopPumpIfIdle();
            return false;
        }

        hosting = false;
        sessionActive = true;
        if (emuBegun)
            l.Begin(emuInstance);
        ensurePump();
        LOGI("joined session at %s", hostAddress.c_str());
        return true;
    }

    void endSession()
    {
        auto guard = lock();
        if (!sessionActive.load())
            return;

        WifiLAN* l = lan.load();
        if (emuBegun)
            l->End(emuInstance);
        l->EndSession();
        sessionActive = false;
        hosting = false;

        // hand the emulator back to the default interface in a clean state
        if (emuBegun)
            MPInterface::Get().Begin(emuInstance);

        stopPumpIfIdle();
        LOGI("session ended");
    }

    std::vector<DiscoveredSession> getDiscoveredSessions()
    {
        std::vector<DiscoveredSession> ret;
        WifiLAN* l = lan.load();
        if (!l)
            return ret;

        for (const auto& [addr, data] : l->GetDiscoveryList())
        {
            in_addr a;
            a.s_addr = htonl(addr);
            char buf[INET_ADDRSTRLEN];
            inet_ntop(AF_INET, &a, buf, sizeof(buf));

            DiscoveredSession s;
            s.address = buf;
            s.name = std::string(data.SessionName, strnlen(data.SessionName, sizeof(data.SessionName)));
            s.numPlayers = data.NumPlayers;
            s.maxPlayers = data.MaxPlayers;
            ret.push_back(s);
        }
        return ret;
    }

    std::vector<WifiLAN::Player> getPlayers()
    {
        WifiLAN* l = lan.load();
        if (!l || !sessionActive.load())
            return {};
        return l->GetPlayerList();
    }

    WifiLAN::Stats getStats()
    {
        WifiLAN* l = lan.load();
        if (!l)
            return WifiLAN::Stats {};
        return l->GetStats();
    }

    void setTuning(const WifiLAN::Tuning& tuning)
    {
        auto guard = lock();
        getLan().SetTuning(tuning);
    }
}
