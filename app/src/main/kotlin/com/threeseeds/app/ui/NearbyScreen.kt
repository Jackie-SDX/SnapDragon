package com.threeseeds.app.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.threeseeds.app.R
import com.threeseeds.app.net.BluetoothLinks
import com.threeseeds.app.net.LinkKind
import com.threeseeds.app.net.LinkPeer
import com.threeseeds.app.state.GameUiState
import com.threeseeds.app.state.LinkStatus

private enum class LobbyTab { HOST, JOIN }

/**
 * Nearby-play lobby: pick a transport, then either wait as host or
 * scan and join. Bluetooth permission prompts are requested lazily,
 * right before the action that needs them.
 */
@Composable
fun NearbyScreen(
    uiState: GameUiState,
    peers: List<LinkPeer>,
    linkError: String?,
    onHost: (LinkKind) -> Unit,
    onScan: (LinkKind) -> Unit,
    onJoin: (LinkPeer) -> Unit,
    onCancel: () -> Unit,
    onClearError: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var kind by remember { mutableStateOf(LinkKind.WIFI) }
    var tab by remember { mutableStateOf(LobbyTab.HOST) }
    val context = androidx.compose.ui.platform.LocalContext.current

    val status = uiState.linkStatus
    val lobbyBusy = status == LinkStatus.HOSTING || status == LinkStatus.SCANNING || status == LinkStatus.CONNECTING

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) {
            when {
                tab == LobbyTab.HOST -> onHost(kind)
                else -> onScan(kind)
            }
        } else {
            onClearError()
        }
    }

    fun withBluetoothPermission(action: () -> Unit) {
        val needed = bluetoothPermissions()
        val granted = needed.all {
            context.checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (granted) action() else permissionLauncher.launch(needed)
    }

    Column(
        modifier = modifier.fillMaxSize().padding(PaddingValues(24.dp)),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { if (lobbyBusy) onCancel() else onBack() }) {
                Text(stringResource(R.string.close))
            }
            Text(
                text = stringResource(R.string.nearby_title),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.weight(1f)
            )
        }

        Text(
            text = stringResource(R.string.nearby_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        )

        // Transport selector
        Row(modifier = Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.Center) {
            TransportChip(
                label = stringResource(R.string.nearby_wifi),
                selected = kind == LinkKind.WIFI,
                enabled = !lobbyBusy
            ) { kind = LinkKind.WIFI }
            Spacer(modifier = Modifier.width(12.dp))
            TransportChip(
                label = stringResource(R.string.nearby_bluetooth),
                selected = kind == LinkKind.BLUETOOTH,
                enabled = !lobbyBusy
            ) { kind = LinkKind.BLUETOOTH }
        }

        // Host / Join tabs
        Row(modifier = Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.Center) {
            TransportChip(
                label = stringResource(R.string.nearby_tab_host),
                selected = tab == LobbyTab.HOST,
                enabled = !lobbyBusy
            ) { tab = LobbyTab.HOST }
            Spacer(modifier = Modifier.width(12.dp))
            TransportChip(
                label = stringResource(R.string.nearby_tab_join),
                selected = tab == LobbyTab.JOIN,
                enabled = !lobbyBusy
            ) { tab = LobbyTab.JOIN }
        }

        Text(
            text = stringResource(if (kind == LinkKind.WIFI) R.string.nearby_hint_wifi else R.string.nearby_hint_bt),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        linkError?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        when (status) {
            LinkStatus.HOSTING -> LobbyWaiting(
                text = stringResource(R.string.nearby_host_waiting),
                onCancel = onCancel
            )

            LinkStatus.SCANNING -> {
                LobbyWaiting(text = stringResource(R.string.nearby_scanning), onCancel = onCancel)
                if (peers.isEmpty()) {
                    Text(
                        text = stringResource(R.string.nearby_no_peers),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 16.dp)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentPadding = PaddingValues(top = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(peers, key = { it.id }) { peer ->
                            PeerRow(peer = peer, busy = status == LinkStatus.CONNECTING) { onJoin(peer) }
                        }
                    }
                }
            }

            LinkStatus.CONNECTING -> LobbyWaiting(text = stringResource(R.string.nearby_connecting), onCancel = onCancel)

            else -> {
                if (tab == LobbyTab.HOST) {
                    Button(
                        onClick = {
                            if (kind == LinkKind.BLUETOOTH) withBluetoothPermission { onHost(kind) }
                            else onHost(kind)
                        },
                        enabled = kind == LinkKind.WIFI || BluetoothLinks.isSupported(),
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) { Text(stringResource(R.string.nearby_host_button)) }
                    if (kind == LinkKind.BLUETOOTH && !BluetoothLinks.isSupported()) {
                        Text(
                            text = stringResource(R.string.nearby_bt_unavailable),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 12.dp)
                        )
                    }
                } else {
                    Button(
                        onClick = {
                            if (kind == LinkKind.BLUETOOTH) withBluetoothPermission { onScan(kind) }
                            else onScan(kind)
                        },
                        enabled = kind == LinkKind.WIFI || BluetoothLinks.isSupported(),
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) { Text(stringResource(R.string.nearby_scan_button)) }
                    if (kind == LinkKind.BLUETOOTH && !BluetoothLinks.isSupported()) {
                        Text(
                            text = stringResource(R.string.nearby_bt_unavailable),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 12.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TransportChip(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(50),
        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
            else androidx.compose.ui.graphics.Color.Transparent,
            contentColor = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onBackground
        )
    ) { Text(label, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal) }
}

@Composable
private fun LobbyWaiting(text: String, onCancel: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(modifier = Modifier.size(40.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 16.dp)
        )
        TextButton(onClick = onCancel, modifier = Modifier.padding(top = 8.dp)) {
            Text(stringResource(R.string.nearby_cancel))
        }
    }
}

@Composable
private fun PeerRow(peer: LinkPeer, busy: Boolean, onJoin: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(peer.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = stringResource(
                        if (peer.kind == LinkKind.WIFI) R.string.nearby_wifi else R.string.nearby_bluetooth
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Button(onClick = onJoin, enabled = !busy) { Text(stringResource(R.string.nearby_join)) }
        }
    }
}

/** Runtime Bluetooth permissions, split by API level (legacy uses install-time + location). */
private fun bluetoothPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= 31) {
    arrayOf(
        Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_SCAN,
    )
} else {
    // Pre-12 discovery needs location; BLUETOOTH/BLUETOOTH_ADMIN are install-time.
    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
}
