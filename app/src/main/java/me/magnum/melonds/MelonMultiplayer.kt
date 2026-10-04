package me.magnum.melonds

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Local wireless multiplayer (DS Download Play, multi-card play) between separate
 * devices over Wi-Fi or a phone hotspot. Works with other Android devices running
 * this build and with PC melonDS (System > Multiplayer > Host/Join LAN game).
 */
object MelonMultiplayer {

    data class DiscoveredSession(val address: String, val name: String, val numPlayers: Int, val maxPlayers: Int)

    enum class PlayerStatus { NONE, CLIENT, HOST, CONNECTING, DISCONNECTED }

    data class Player(val id: Int, val name: String, val status: PlayerStatus, val isLocal: Boolean, val pingMs: Int)

    data class Stats(
        val exchanges: Long,
        val replyTimeouts: Long,
        val staleDrops: Long,
        val duplicateDrops: Long,
        val redundantSent: Long,
        val avgExchangeMs: Float,
        val maxExchangeMs: Long,
        val currentTimeoutMs: Long,
        val redundantPeersMask: Long,
    )

    private const val SEP = '\u001F'

    private var wifiLock: WifiManager.WifiLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    external fun startDiscovery(): Boolean
    external fun stopDiscovery()
    external fun startHost(playerName: String, maxPlayers: Int): Boolean
    external fun startClient(playerName: String, hostAddress: String): Boolean
    external fun endSession()
    external fun isSessionActive(): Boolean
    external fun isHost(): Boolean
    external fun setTuning(adaptive: Boolean, minTimeoutMs: Int, maxTimeoutMs: Int, redundancy: Boolean)

    private external fun getDiscoveredSessionsInternal(): Array<String>
    private external fun getPlayersInternal(): Array<String>
    private external fun getStatsInternal(): LongArray

    fun getDiscoveredSessions(): List<DiscoveredSession> {
        return getDiscoveredSessionsInternal().mapNotNull {
            val f = it.split(SEP)
            if (f.size < 4) null else DiscoveredSession(f[0], f[1], f[2].toIntOrNull() ?: 0, f[3].toIntOrNull() ?: 0)
        }
    }

    fun getPlayers(): List<Player> {
        return getPlayersInternal().mapNotNull {
            val f = it.split(SEP)
            if (f.size < 5) return@mapNotNull null
            Player(
                id = f[0].toIntOrNull() ?: 0,
                name = f[1],
                status = PlayerStatus.entries.getOrElse(f[2].toIntOrNull() ?: 0) { PlayerStatus.NONE },
                isLocal = f[3] == "1",
                pingMs = f[4].toIntOrNull() ?: 0,
            )
        }
    }

    fun getStats(): Stats {
        val v = getStatsInternal()
        return Stats(v[0], v[1], v[2], v[3], v[4], v[5] / 1000f, v[6], v[7], v[8])
    }

    /**
     * Keep the Wi-Fi radio awake and responsive. Without this Android puts the
     * radio into power-save between packets, which adds 50-300ms spikes -- the DS
     * protocol expects replies within microseconds, so those spikes are what
     * usually breaks local multiplayer over Wi-Fi.
     */
    @SuppressLint("WakelockTimeout")
    fun acquireNetworkLocks(context: Context) {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return

        if (wifiLock == null) {
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wifiManager.createWifiLock(mode, "melonDS:localmp").apply {
                setReferenceCounted(false)
                acquire()
            }
        }

        // needed to receive the discovery broadcasts on many devices
        if (multicastLock == null) {
            multicastLock = wifiManager.createMulticastLock("melonDS:localmp-discovery").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    fun releaseNetworkLocks() {
        wifiLock?.takeIf { it.isHeld }?.release()
        wifiLock = null
        multicastLock?.takeIf { it.isHeld }?.release()
        multicastLock = null
    }

    /** IPv4 addresses of this device, so the other player can join by typing one in. */
    fun getLocalAddresses(): List<Pair<String, String>> {
        return try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { iface ->
                    iface.inetAddresses.toList()
                        .filterIsInstance<Inet4Address>()
                        .map { iface.name to (it.hostAddress ?: "") }
                }
                .filter { it.second.isNotEmpty() }
                // Wi-Fi and hotspot interfaces first; cellular last
                .sortedBy { (name, _) ->
                    when {
                        name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("swlan") -> 0
                        name.startsWith("p2p") -> 1
                        name.startsWith("rmnet") || name.startsWith("ccmni") -> 3
                        else -> 2
                    }
                }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
