package me.magnum.melonds.ui.localmultiplayer

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.Card
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Scaffold
import androidx.compose.material.Slider
import androidx.compose.material.Switch
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.magnum.melonds.MelonMultiplayer
import me.magnum.melonds.R
import me.magnum.melonds.domain.model.ConsoleType
import me.magnum.melonds.domain.model.rom.Rom
import me.magnum.melonds.ui.common.rom.EmulatorLaunchValidatorDelegate
import me.magnum.melonds.ui.emulator.EmulatorActivity
import me.magnum.melonds.ui.theme.MelonTheme

@AndroidEntryPoint
class LocalMultiplayerActivity : AppCompatActivity() {

    companion object {
        private const val PREF_PLAYER_NAME = "local_mp_player_name"
        private const val PREF_MAX_WAIT = "local_mp_max_wait"
        private const val PREF_ADAPTIVE = "local_mp_adaptive"
        private const val PREF_REDUNDANCY = "local_mp_redundancy"

        /** Applies the saved tuning. Safe to call before any session exists. */
        fun applySavedTuning(context: Context) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            MelonMultiplayer.setTuning(
                adaptive = prefs.getBoolean(PREF_ADAPTIVE, true),
                minTimeoutMs = 25,
                maxTimeoutMs = prefs.getInt(PREF_MAX_WAIT, 200),
                redundancy = prefs.getBoolean(PREF_REDUNDANCY, true),
            )
        }
    }

    private lateinit var launchValidator: EmulatorLaunchValidatorDelegate

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)

        launchValidator = EmulatorLaunchValidatorDelegate(this, object : EmulatorLaunchValidatorDelegate.Callback {
            override fun onRomValidated(rom: Rom) {
                startActivity(EmulatorActivity.getRomEmulatorActivityIntent(this@LocalMultiplayerActivity, rom))
            }

            override fun onFirmwareValidated(consoleType: ConsoleType) {
                startActivity(EmulatorActivity.getFirmwareEmulatorActivityIntent(this@LocalMultiplayerActivity, consoleType))
            }

            override fun onValidationAborted() {
            }
        })

        applySavedTuning(this)
        MelonMultiplayer.acquireNetworkLocks(this)

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val defaultName = prefs.getString(PREF_PLAYER_NAME, null)
            ?: prefs.getString("firmware_settings_nickname", null)
            ?: "Player"

        setContent {
            MelonTheme {
                LocalMultiplayerScreen(
                    initialName = defaultName,
                    initialMaxWait = prefs.getInt(PREF_MAX_WAIT, 200),
                    initialAdaptive = prefs.getBoolean(PREF_ADAPTIVE, true),
                    initialRedundancy = prefs.getBoolean(PREF_REDUNDANCY, true),
                    onNameChanged = { prefs.edit { putString(PREF_PLAYER_NAME, it) } },
                    onTuningChanged = { adaptive, maxWait, redundancy ->
                        prefs.edit {
                            putBoolean(PREF_ADAPTIVE, adaptive)
                            putInt(PREF_MAX_WAIT, maxWait)
                            putBoolean(PREF_REDUNDANCY, redundancy)
                        }
                        applySavedTuning(this@LocalMultiplayerActivity)
                    },
                    onPickGame = { finish() },
                    onOpenDownloadPlay = { launchValidator.validateFirmware(ConsoleType.DS) },
                    onBackClick = { onSupportNavigateUp() },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        MelonMultiplayer.acquireNetworkLocks(this)
        if (!MelonMultiplayer.isSessionActive()) {
            MelonMultiplayer.startDiscovery()
        }
    }

    override fun onStop() {
        super.onStop()
        MelonMultiplayer.stopDiscovery()
        // keep the radio in low-latency mode while a session is running, since
        // the game itself runs in another activity
        if (!MelonMultiplayer.isSessionActive()) {
            MelonMultiplayer.releaseNetworkLocks()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}

@Composable
private fun LocalMultiplayerScreen(
    initialName: String,
    initialMaxWait: Int,
    initialAdaptive: Boolean,
    initialRedundancy: Boolean,
    onNameChanged: (String) -> Unit,
    onTuningChanged: (adaptive: Boolean, maxWait: Int, redundancy: Boolean) -> Unit,
    onPickGame: () -> Unit,
    onOpenDownloadPlay: () -> Unit,
    onBackClick: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    var playerName by remember { mutableStateOf(initialName) }
    var maxPlayers by remember { mutableIntStateOf(2) }
    var hostAddress by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    var sessionActive by remember { mutableStateOf(MelonMultiplayer.isSessionActive()) }
    var isHost by remember { mutableStateOf(MelonMultiplayer.isHost()) }
    var sessions by remember { mutableStateOf(emptyList<MelonMultiplayer.DiscoveredSession>()) }
    var players by remember { mutableStateOf(emptyList<MelonMultiplayer.Player>()) }
    var stats by remember { mutableStateOf<MelonMultiplayer.Stats?>(null) }
    var addresses by remember { mutableStateOf(MelonMultiplayer.getLocalAddresses()) }

    var adaptive by remember { mutableStateOf(initialAdaptive) }
    var maxWait by remember { mutableFloatStateOf(initialMaxWait.toFloat()) }
    var redundancy by remember { mutableStateOf(initialRedundancy) }
    var showAdvanced by remember { mutableStateOf(false) }

    // poll native state; cheap, and keeps this screen free of callbacks from the emulator thread
    LaunchedEffect(Unit) {
        while (true) {
            sessionActive = MelonMultiplayer.isSessionActive()
            isHost = MelonMultiplayer.isHost()
            if (sessionActive) {
                players = MelonMultiplayer.getPlayers()
                stats = MelonMultiplayer.getStats()
            } else {
                sessions = MelonMultiplayer.getDiscoveredSessions()
                addresses = MelonMultiplayer.getLocalAddresses()
            }
            delay(500)
        }
    }

    fun join(address: String) {
        if (busy || address.isBlank()) return
        busy = true
        scope.launch {
            val name = playerName.ifBlank { "Player" }
            val ok = withContext(Dispatchers.IO) {
                MelonMultiplayer.startClient(name, address.trim())
            }
            busy = false
            if (!ok) {
                Toast.makeText(context, context.getString(R.string.localmp_join_failed, address), Toast.LENGTH_LONG).show()
                MelonMultiplayer.startDiscovery()
            }
            sessionActive = MelonMultiplayer.isSessionActive()
        }
    }

    Scaffold(
        topBar = {
            Box(Modifier.background(MaterialTheme.colors.primaryVariant).statusBarsPadding()) {
                TopAppBar(
                    title = { Text(stringResource(R.string.local_multiplayer)) },
                    backgroundColor = MaterialTheme.colors.primary,
                    navigationIcon = {
                        IconButton(onClick = onBackClick) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                        }
                    },
                    windowInsets = WindowInsets.safeDrawing.exclude(WindowInsets(bottom = Int.MAX_VALUE)),
                )
            }
        },
        backgroundColor = MaterialTheme.colors.surface,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (!sessionActive) {
                OutlinedTextField(
                    value = playerName,
                    onValueChange = {
                        playerName = it.take(31)
                        onNameChanged(playerName)
                    },
                    label = { Text(stringResource(R.string.localmp_player_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Section(stringResource(R.string.localmp_host_title)) {
                    Text(stringResource(R.string.localmp_host_description), style = MaterialTheme.typography.body2)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.localmp_max_players, maxPlayers), modifier = Modifier.weight(1f))
                        listOf(2, 3, 4).forEach { n ->
                            TextButton(onClick = { maxPlayers = n }, enabled = maxPlayers != n) { Text(n.toString()) }
                        }
                    }
                    Button(
                        onClick = {
                            val ok = MelonMultiplayer.startHost(playerName.ifBlank { "Player" }, maxPlayers)
                            if (!ok) Toast.makeText(context, R.string.localmp_host_failed, Toast.LENGTH_LONG).show()
                            sessionActive = MelonMultiplayer.isSessionActive()
                        },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.localmp_host_button)) }
                }

                Section(stringResource(R.string.localmp_join_title)) {
                    Text(stringResource(R.string.localmp_join_description), style = MaterialTheme.typography.body2)
                    if (sessions.isEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.localmp_searching), style = MaterialTheme.typography.body2)
                        }
                    }
                    sessions.forEach { s ->
                        Text(
                            text = stringResource(R.string.localmp_session_entry, s.name, s.numPlayers, s.maxPlayers, s.address),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !busy) { join(s.address) }
                                .padding(vertical = 12.dp),
                        )
                        Divider()
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = hostAddress,
                            onValueChange = { hostAddress = it.trim() },
                            label = { Text(stringResource(R.string.localmp_host_address)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { join(hostAddress) }, enabled = !busy && hostAddress.isNotBlank()) {
                            Text(stringResource(R.string.localmp_join_button))
                        }
                    }
                    if (busy) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.localmp_joining))
                        }
                    }
                }

                Section(stringResource(R.string.localmp_my_addresses)) {
                    if (addresses.isEmpty()) {
                        Text(stringResource(R.string.localmp_no_addresses), style = MaterialTheme.typography.body2)
                    }
                    addresses.forEach { (iface, addr) ->
                        Text("$addr  ($iface)", style = MaterialTheme.typography.body1)
                    }
                }

                Section(stringResource(R.string.localmp_tips_title)) {
                    Text(stringResource(R.string.localmp_tips), style = MaterialTheme.typography.body2)
                }
            } else {
                Section(stringResource(if (isHost) R.string.localmp_status_hosting else R.string.localmp_status_connected)) {
                    Text(
                        stringResource(if (isHost) R.string.localmp_host_next_steps else R.string.localmp_client_next_steps),
                        style = MaterialTheme.typography.body2,
                    )
                    if (!isHost) {
                        Button(onClick = onOpenDownloadPlay, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.localmp_open_download_play))
                        }
                    }
                    OutlinedButton(onClick = onPickGame, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.localmp_pick_game))
                    }
                }

                Section(stringResource(R.string.localmp_players)) {
                    players.forEach { p ->
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text(
                                text = if (p.isLocal) stringResource(R.string.localmp_player_you, p.name) else p.name,
                                modifier = Modifier.weight(1f),
                                fontWeight = if (p.status == MelonMultiplayer.PlayerStatus.HOST) FontWeight.Bold else FontWeight.Normal,
                            )
                            if (!p.isLocal) Text(stringResource(R.string.localmp_player_ping, p.pingMs))
                        }
                    }
                }

                stats?.let { s ->
                    Section(stringResource(R.string.localmp_link_title)) {
                        val totalRounds = s.exchanges + s.replyTimeouts
                        Text(
                            stringResource(
                                R.string.localmp_link_stats,
                                s.avgExchangeMs,
                                s.maxExchangeMs.toInt(),
                                s.currentTimeoutMs.toInt(),
                                s.replyTimeouts.toInt(),
                                totalRounds.toInt(),
                                stringResource(if (s.redundantPeersMask != 0L) R.string.localmp_redundancy_on else R.string.localmp_redundancy_off),
                            ),
                            style = MaterialTheme.typography.body2,
                        )
                    }
                }

                OutlinedButton(
                    onClick = {
                        MelonMultiplayer.endSession()
                        sessionActive = false
                        MelonMultiplayer.startDiscovery()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.localmp_leave)) }
            }

            TextButton(onClick = { showAdvanced = !showAdvanced }) { Text(stringResource(R.string.localmp_advanced)) }
            if (showAdvanced) {
                Section(null) {
                    SwitchRow(stringResource(R.string.localmp_adaptive_wait), adaptive) {
                        adaptive = it
                        onTuningChanged(adaptive, maxWait.toInt(), redundancy)
                    }
                    Text(stringResource(R.string.localmp_max_wait, maxWait.toInt()))
                    Slider(
                        value = maxWait,
                        onValueChange = { maxWait = (it / 10f).toInt() * 10f },
                        onValueChangeFinished = { onTuningChanged(adaptive, maxWait.toInt(), redundancy) },
                        valueRange = 25f..500f,
                    )
                    Text(stringResource(R.string.localmp_max_wait_hint), style = MaterialTheme.typography.caption)
                    SwitchRow(stringResource(R.string.localmp_redundancy), redundancy) {
                        redundancy = it
                        onTuningChanged(adaptive, maxWait.toInt(), redundancy)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun Section(title: String?, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), elevation = 2.dp) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (title != null) {
                Text(title, style = MaterialTheme.typography.h6)
            }
            content()
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
