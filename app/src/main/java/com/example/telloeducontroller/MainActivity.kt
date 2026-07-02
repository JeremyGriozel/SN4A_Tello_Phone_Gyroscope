package com.example.telloeducontroller

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.telloeducontroller.sensor.rememberGyroscopeState
import com.example.telloeducontroller.ui.Cube3DView
import com.example.telloeducontroller.ui.TelloControlScreen
import com.example.telloeducontroller.ui.theme.TelloEDUControllerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Le pilotage se fait principalement par inclinaison du téléphone : on évite que
        // l'écran ne s'éteigne tout seul en plein vol faute de contact tactile.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            TelloEDUControllerTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    TelloApp(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

private enum class AppScreen { PILOTAGE, GYROSCOPE_3D }

@Composable
fun TelloApp(modifier: Modifier = Modifier) {
    var screen by remember { mutableStateOf(AppScreen.PILOTAGE) }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TextButton(onClick = { screen = AppScreen.PILOTAGE }) { Text("Pilotage") }
            TextButton(onClick = { screen = AppScreen.GYROSCOPE_3D }) { Text("Cube 3D") }
        }

        when (screen) {
            AppScreen.PILOTAGE -> TelloControlScreen(modifier = Modifier.weight(1f))
            AppScreen.GYROSCOPE_3D -> GyroscopeScreen(modifier = Modifier.weight(1f))
        }
    }
}

@Composable
fun GyroscopeScreen(modifier: Modifier = Modifier) {
    val gyroscope = rememberGyroscopeState()
    val reading by gyroscope.reading

    if (!reading.sensorAvailable) {
        Column(
            modifier = modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(text = "Aucun capteur gyroscope détecté sur cet appareil.")
        }
        return
    }

    Row(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(text = "Vitesse angulaire (rad/s)", fontFamily = FontFamily.Monospace)
            Text(text = "X: %+.3f".format(reading.angularVelocity[0]), fontFamily = FontFamily.Monospace)
            Text(text = "Y: %+.3f".format(reading.angularVelocity[1]), fontFamily = FontFamily.Monospace)
            Text(text = "Z: %+.3f".format(reading.angularVelocity[2]), fontFamily = FontFamily.Monospace)

            Spacer(modifier = Modifier.height(16.dp))

            Button(onClick = gyroscope.calibrate, enabled = !reading.isCalibrating) {
                Text(if (reading.isCalibrating) "Calibrage en cours..." else "Calibrer")
            }
            if (reading.isCalibrating) {
                Text(text = "Ne bougez pas le téléphone", fontFamily = FontFamily.Monospace)
            }
        }

        Cube3DView(
            rotationMatrix = reading.rotationMatrix,
            modifier = Modifier.weight(1f)
        )
    }
}
