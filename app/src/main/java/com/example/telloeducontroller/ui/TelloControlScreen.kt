package com.example.telloeducontroller.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.telloeducontroller.sensor.rememberAttitudeState
import com.example.telloeducontroller.tello.AppLog
import com.example.telloeducontroller.tello.TelloConnectionState
import com.example.telloeducontroller.tello.rememberTelloController
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private const val MAX_TILT_DEG = 30f // inclinaison du téléphone pour un manche à fond (roulis/tangage)
private const val MAX_YAW_DEG = 45f // rotation du téléphone pour un manche à fond (lacet)
private const val DEADZONE_DEG = 3f
private const val ALTITUDE_STICK = 60

@Composable
fun TelloControlScreen(modifier: Modifier = Modifier) {
    val attitudeHandle = rememberAttitudeState()
    val attitude by attitudeHandle.attitude
    val tello = rememberTelloController()
    val telloState by tello.uiState
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var showLogs by remember { mutableStateOf(false) }
    val logVersion by AppLog.version
    val logLines = remember(logVersion) { AppLog.snapshot() }

    LaunchedEffect(Unit) { tello.connect() }

    val ascendInteraction = remember { MutableInteractionSource() }
    val descendInteraction = remember { MutableInteractionSource() }
    val ascendPressed by ascendInteraction.collectIsPressedAsState()
    val descendPressed by descendInteraction.collectIsPressedAsState()

    if (!attitude.sensorAvailable) {
        Column(
            modifier = modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("Capteur d'orientation indisponible sur cet appareil.")
        }
        return
    }

    val rollStick = stickFromAngle(attitude.rollDeg, MAX_TILT_DEG)
    val pitchStick = stickFromAngle(-attitude.pitchDeg, MAX_TILT_DEG)
    val yawStick = stickFromAngle(attitude.yawDeg, MAX_YAW_DEG)
    val throttleStick = when {
        ascendPressed && !descendPressed -> ALTITUDE_STICK
        descendPressed && !ascendPressed -> -ALTITUDE_STICK
        else -> 0
    }

    SideEffect {
        tello.updateSticks(roll = rollStick, pitch = pitchStick, throttle = throttleStick, yaw = yawStick)
    }

    Row(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Tello EDU Controller", style = MaterialTheme.typography.titleLarge)

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { scope.launch { tello.takeoff() } },
                    enabled = telloState.connection == TelloConnectionState.CONNECTED && !telloState.isFlying,
                    modifier = Modifier.height(56.dp)
                ) { Text("Décoller") }

                Button(
                    onClick = { scope.launch { tello.land() } },
                    enabled = telloState.connection == TelloConnectionState.CONNECTED && telloState.isFlying,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.height(56.dp)
                ) { Text("Atterrir") }
            }

            if (telloState.connection == TelloConnectionState.FAILED) {
                Button(onClick = { tello.connect() }) { Text("Réessayer la connexion") }
            }

            Text(text = "État : ${telloState.connection.toLabel()}")
            Text(text = "Batterie : ${telloState.batteryPercent?.let { "$it %" } ?: "—"}")
            Text(text = telloState.lastMessage, fontFamily = FontFamily.Monospace)

            Spacer(Modifier.height(8.dp))
            Text(
                text = "Roulis %+.1f°  Tangage %+.1f°  Lacet %+.1f°".format(
                    attitude.rollDeg, attitude.pitchDeg, attitude.yawDeg
                ),
                fontFamily = FontFamily.Monospace
            )
            Button(onClick = attitudeHandle.center) { Text("Centrer la manette") }

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { showLogs = !showLogs }) {
                    Text(if (showLogs) "Masquer les logs" else "Afficher les logs (${logLines.size})")
                }
                if (showLogs) {
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(logLines.joinToString("\n")))
                    }) { Text("Copier") }
                    TextButton(onClick = { AppLog.clear() }) { Text("Effacer") }
                }
            }
            if (showLogs) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(4.dp)
                ) {
                    items(logLines.asReversed()) { line ->
                        Text(text = line, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                    }
                }
            }
        }

        Spacer(Modifier.width(16.dp))

        // Colonne de droite : pensée pour être actionnée au pouce droit (monter / descendre).
        Column(
            modifier = Modifier.fillMaxHeight(),
            verticalArrangement = Arrangement.SpaceEvenly,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Button(
                onClick = {},
                interactionSource = ascendInteraction,
                enabled = telloState.isFlying,
                modifier = Modifier.size(width = 100.dp, height = 150.dp)
            ) { Text("▲\nMonter", textAlign = TextAlign.Center) }

            Button(
                onClick = {},
                interactionSource = descendInteraction,
                enabled = telloState.isFlying,
                modifier = Modifier.size(width = 100.dp, height = 150.dp)
            ) { Text("▼\nDescendre", textAlign = TextAlign.Center) }
        }
    }
}

private fun stickFromAngle(angleDeg: Float, maxDeg: Float): Int {
    val magnitude = abs(angleDeg)
    if (magnitude < DEADZONE_DEG) return 0
    val sign = if (angleDeg < 0) -1 else 1
    val scaled = (magnitude - DEADZONE_DEG) / (maxDeg - DEADZONE_DEG) * 100f
    return (sign * scaled).roundToInt().coerceIn(-100, 100)
}

private fun TelloConnectionState.toLabel(): String = when (this) {
    TelloConnectionState.DISCONNECTED -> "Déconnecté"
    TelloConnectionState.CONNECTING -> "Connexion..."
    TelloConnectionState.CONNECTED -> "Connecté"
    TelloConnectionState.FAILED -> "Échec"
}
