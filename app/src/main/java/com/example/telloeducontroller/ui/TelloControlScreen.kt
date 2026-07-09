package com.example.telloeducontroller.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
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
private const val DEADZONE_DEG = 5f // zone morte franche autour du zéro pour éviter le bruit de tenue en main
private const val MAX_STICK_MAGNITUDE = 40 // plafonne la vitesse max de rotation/inclinaison (au lieu de 100) pour un pilotage plus doux
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

    // Décorrélé du décollage : au décollage, le drone ne doit que décoller et se stabiliser
    // (roll/pitch/yaw à 0) tant que le pilote n'a pas explicitement activé le gyroscope. Repassé à
    // false à l'atterrissage pour repartir systématiquement stabilisé au vol suivant.
    var gyroControlEnabled by remember { mutableStateOf(false) }
    LaunchedEffect(telloState.isFlying) {
        if (!telloState.isFlying) gyroControlEnabled = false
    }

    LaunchedEffect(Unit) { tello.connect() }

    val ascendInteraction = remember { MutableInteractionSource() }
    val descendInteraction = remember { MutableInteractionSource() }
    val ascendPressed by ascendInteraction.collectIsPressedAsState()
    val descendPressed by descendInteraction.collectIsPressedAsState()

    if (!attitude.sensorAvailable) {
        Column(
            modifier = modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("⚠️", style = MaterialTheme.typography.displayMedium)
            Spacer(Modifier.height(12.dp))
            Text(
                "Capteur d'orientation indisponible sur cet appareil.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center
            )
        }
        return
    }

    val rollStick = if (gyroControlEnabled) stickFromAngle(attitude.rollDeg, MAX_TILT_DEG) else 0
    val pitchStick = if (gyroControlEnabled) stickFromAngle(attitude.pitchDeg, MAX_TILT_DEG) else 0
    val yawStick = if (gyroControlEnabled) stickFromAngle(attitude.yawDeg, MAX_YAW_DEG) else 0
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
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Tello EDU Controller", style = MaterialTheme.typography.headlineSmall)

            // --- État du drone (connexion, batterie, altitude) ---
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(telloState.connection.toColor(), CircleShape)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(telloState.connection.toLabel(), style = MaterialTheme.typography.titleMedium)
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Batterie", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                telloState.batteryPercent?.let { "$it %" } ?: "—",
                                style = MaterialTheme.typography.bodyMedium,
                                color = telloState.batteryPercent.toBatteryColor()
                            )
                        }
                        LinearProgressIndicator(
                            progress = { (telloState.batteryPercent ?: 0) / 100f },
                            modifier = Modifier.fillMaxWidth(),
                            color = telloState.batteryPercent.toBatteryColor()
                        )
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Altitude", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${telloState.heightCm?.let { "$it cm" } ?: "—"}  ·  consigne ${telloState.targetHeightCm} cm",
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    if (telloState.lastMessage.isNotBlank()) {
                        HorizontalDivider()
                        Text(
                            text = telloState.lastMessage,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // --- Commandes de vol ---
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Vol", style = MaterialTheme.typography.titleMedium)

                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = { scope.launch { tello.takeoff() } },
                            enabled = telloState.connection == TelloConnectionState.CONNECTED && !telloState.isFlying,
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp)
                        ) { Text("🛫 Décoller") }

                        Button(
                            onClick = { scope.launch { tello.land() } },
                            enabled = telloState.connection == TelloConnectionState.CONNECTED && telloState.isFlying,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp)
                        ) { Text("🛬 Atterrir") }
                    }

                    if (telloState.connection == TelloConnectionState.FAILED) {
                        OutlinedButton(onClick = { tello.connect() }, modifier = Modifier.fillMaxWidth()) {
                            Text("Réessayer la connexion")
                        }
                    }

                    Button(
                        onClick = {
                            // Recentre sur la position actuelle du téléphone à l'activation, pour éviter
                            // un à-coup si le téléphone n'est pas exactement à plat/vertical à ce moment-là.
                            if (!gyroControlEnabled) attitudeHandle.center()
                            gyroControlEnabled = !gyroControlEnabled
                        },
                        enabled = telloState.isFlying,
                        modifier = Modifier.fillMaxWidth(),
                        colors = if (gyroControlEnabled) {
                            ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
                        } else {
                            ButtonDefaults.buttonColors()
                        }
                    ) { Text(if (gyroControlEnabled) "🎮 Désactiver le contrôle gyroscopique" else "🎮 Activer le contrôle gyroscopique") }
                }
            }

            // --- Attitude du téléphone ---
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Attitude du téléphone", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "Roulis %+.1f°   Tangage %+.1f°   Lacet %+.1f°".format(
                            attitude.rollDeg, attitude.pitchDeg, attitude.yawDeg
                        ),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    OutlinedButton(onClick = attitudeHandle.center, modifier = Modifier.fillMaxWidth()) {
                        Text("Centrer la manette")
                    }
                }
            }

            // --- Logs ---
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { showLogs = !showLogs }) {
                        Text(if (showLogs) "▾ Masquer les logs" else "▸ Afficher les logs (${logLines.size})")
                    }
                    if (showLogs) {
                        TextButton(onClick = {
                            clipboard.setText(AnnotatedString(logLines.joinToString("\n")))
                        }) { Text("Copier") }
                        TextButton(onClick = { AppLog.clear() }) { Text("Effacer") }
                    }
                }
                if (showLogs) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.medium
                    ) {
                        LazyColumn(modifier = Modifier.padding(8.dp)) {
                            items(logLines.asReversed()) { line ->
                                Text(text = line, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                            }
                        }
                    }
                }
            }
        }

        // Colonne de droite : pensée pour être actionnée au pouce droit (monter / descendre).
        ElevatedCard(
            modifier = Modifier
                .fillMaxHeight()
                .width(120.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                verticalArrangement = Arrangement.SpaceEvenly,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Altitude", style = MaterialTheme.typography.labelMedium)

                Button(
                    onClick = {},
                    interactionSource = ascendInteraction,
                    enabled = telloState.isFlying,
                    modifier = Modifier.size(width = 96.dp, height = 130.dp)
                ) { Text("▲\nMonter", textAlign = TextAlign.Center) }

                Button(
                    onClick = {},
                    interactionSource = descendInteraction,
                    enabled = telloState.isFlying,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    ),
                    modifier = Modifier.size(width = 96.dp, height = 130.dp)
                ) { Text("▼\nDescendre", textAlign = TextAlign.Center) }
            }
        }
    }
}

private fun stickFromAngle(angleDeg: Float, maxDeg: Float): Int {
    val magnitude = abs(angleDeg)
    if (magnitude < DEADZONE_DEG) return 0
    val sign = if (angleDeg < 0) -1 else 1
    val scaled = (magnitude - DEADZONE_DEG) / (maxDeg - DEADZONE_DEG) * MAX_STICK_MAGNITUDE
    return (sign * scaled).roundToInt().coerceIn(-MAX_STICK_MAGNITUDE, MAX_STICK_MAGNITUDE)
}

private fun TelloConnectionState.toLabel(): String = when (this) {
    TelloConnectionState.DISCONNECTED -> "Déconnecté"
    TelloConnectionState.CONNECTING -> "Connexion..."
    TelloConnectionState.CONNECTED -> "Connecté"
    TelloConnectionState.FAILED -> "Échec"
}

@Composable
private fun TelloConnectionState.toColor(): Color = when (this) {
    TelloConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.outline
    TelloConnectionState.CONNECTING -> MaterialTheme.colorScheme.tertiary
    TelloConnectionState.CONNECTED -> MaterialTheme.colorScheme.primary
    TelloConnectionState.FAILED -> MaterialTheme.colorScheme.error
}

@Composable
private fun Int?.toBatteryColor(): Color = when {
    this == null -> MaterialTheme.colorScheme.onSurfaceVariant
    this <= 15 -> MaterialTheme.colorScheme.error
    this <= 30 -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.primary
}
