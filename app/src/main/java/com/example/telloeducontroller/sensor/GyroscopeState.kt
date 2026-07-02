package com.example.telloeducontroller.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Vitesse angulaire corrigée du biais (rad/s) et matrice de rotation 3x3 (row-major) accumulée
 * depuis le dernier calibrage, utilisée pour orienter le cube 3D.
 */
data class GyroscopeReading(
    val angularVelocity: FloatArray = floatArrayOf(0f, 0f, 0f),
    val rotationMatrix: FloatArray = identityMatrix(),
    val sensorAvailable: Boolean = true,
    val isCalibrating: Boolean = false
) {
    companion object {
        fun identityMatrix() = floatArrayOf(
            1f, 0f, 0f,
            0f, 1f, 0f,
            0f, 0f, 1f
        )
    }
}

/** Poignée exposée à l'UI pour lire l'état du gyroscope et déclencher un calibrage. */
class GyroscopeHandle(val reading: State<GyroscopeReading>, val calibrate: () -> Unit)

private const val CALIBRATION_DURATION_NS = 1_500_000_000L // 1,5 s, téléphone immobile

/**
 * Enregistre un listener sur le capteur gyroscope et expose sa lecture ainsi qu'une action de
 * calibrage. Le calibrage mesure le biais du capteur (dérive au repos) pendant que le téléphone
 * est immobile, puis le soustrait des lectures suivantes et réinitialise l'orientation du cube.
 */
@Composable
fun rememberGyroscopeState(): GyroscopeHandle {
    val context = LocalContext.current
    val reading = remember { mutableStateOf(GyroscopeReading()) }
    val calibrationRequested = remember { BooleanRef() }

    DisposableEffect(context) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        if (gyroscope == null) {
            reading.value = GyroscopeReading(sensorAvailable = false)
            return@DisposableEffect onDispose {}
        }

        val rotationMatrix = GyroscopeReading.identityMatrix()
        val bias = floatArrayOf(0f, 0f, 0f)
        val calibrationSum = floatArrayOf(0f, 0f, 0f)
        var calibrationSamples = 0
        var calibrationStartTimestamp = 0L
        var calibrating = false
        var lastTimestamp = 0L

        // L'écran est verrouillé en paysage, mais le capteur reporte toujours ses valeurs par
        // rapport à l'orientation naturelle (portrait) de l'appareil : il faut donc remapper les
        // axes X/Y sur ceux de l'écran affiché pour que le cube tourne dans le bon sens.
        val displayRotation = currentDisplayRotation(context)

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (calibrationRequested.value) {
                    calibrationRequested.value = false
                    calibrating = true
                    calibrationStartTimestamp = event.timestamp
                    calibrationSamples = 0
                    calibrationSum[0] = 0f
                    calibrationSum[1] = 0f
                    calibrationSum[2] = 0f
                    rotationMatrix.copyFrom(GyroscopeReading.identityMatrix())
                }

                val (rawX, rawY, rawZ) = event.values
                val (wx, wy, wz) = remapForDisplayRotation(rawX, rawY, rawZ, displayRotation)

                if (calibrating) {
                    calibrationSum[0] += wx
                    calibrationSum[1] += wy
                    calibrationSum[2] += wz
                    calibrationSamples++

                    if (event.timestamp - calibrationStartTimestamp >= CALIBRATION_DURATION_NS) {
                        bias[0] = calibrationSum[0] / calibrationSamples
                        bias[1] = calibrationSum[1] / calibrationSamples
                        bias[2] = calibrationSum[2] / calibrationSamples
                        calibrating = false
                        lastTimestamp = event.timestamp
                    }

                    reading.value = GyroscopeReading(
                        angularVelocity = floatArrayOf(0f, 0f, 0f),
                        rotationMatrix = rotationMatrix.copyOf(),
                        isCalibrating = true
                    )
                    return
                }

                val cx = wx - bias[0]
                val cy = wy - bias[1]
                val cz = wz - bias[2]

                if (lastTimestamp != 0L) {
                    val dt = (event.timestamp - lastTimestamp) * NS2S
                    integrateRotation(rotationMatrix, cx, cy, cz, dt)
                }
                lastTimestamp = event.timestamp

                reading.value = GyroscopeReading(
                    angularVelocity = floatArrayOf(cx, cy, cz),
                    rotationMatrix = rotationMatrix.copyOf()
                )
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        sensorManager.registerListener(listener, gyroscope, SensorManager.SENSOR_DELAY_GAME)

        onDispose {
            sensorManager.unregisterListener(listener)
        }
    }

    return GyroscopeHandle(reading) { calibrationRequested.value = true }
}

/** Simple porte-drapeau mutable partagé entre le bouton "Calibrer" et le listener du capteur. */
private class BooleanRef(var value: Boolean = false)

/**
 * Remappe le vecteur du capteur (exprimé dans le repère d'orientation naturelle de l'appareil)
 * vers le repère de l'écran actuellement affiché. L'axe Z (perpendiculaire à l'écran) est
 * invariant par rotation dans le plan de l'écran.
 */
private fun remapForDisplayRotation(x: Float, y: Float, z: Float, rotation: Int): FloatArray =
    when (rotation) {
        Surface.ROTATION_90 -> floatArrayOf(y, -x, z)
        Surface.ROTATION_180 -> floatArrayOf(-x, -y, z)
        Surface.ROTATION_270 -> floatArrayOf(-y, x, z)
        else -> floatArrayOf(x, y, z)
    }

private fun FloatArray.copyFrom(other: FloatArray) {
    other.copyInto(this)
}

private const val NS2S = 1f / 1_000_000_000f

/** Intègre la vitesse angulaire dans [rotationMatrix] sur [dt] secondes (formule de Rodrigues). */
private fun integrateRotation(rotationMatrix: FloatArray, wx: Float, wy: Float, wz: Float, dt: Float) {
    val omegaMagnitude = sqrt(wx * wx + wy * wy + wz * wz)
    if (omegaMagnitude < 1e-6f) return

    val axisX = wx / omegaMagnitude
    val axisY = wy / omegaMagnitude
    val axisZ = wz / omegaMagnitude
    val angle = omegaMagnitude * dt

    val s = sin(angle)
    val c = cos(angle)
    val oneMinusCos = 1f - c

    val delta = floatArrayOf(
        c + axisX * axisX * oneMinusCos,
        axisX * axisY * oneMinusCos - axisZ * s,
        axisX * axisZ * oneMinusCos + axisY * s,

        axisY * axisX * oneMinusCos + axisZ * s,
        c + axisY * axisY * oneMinusCos,
        axisY * axisZ * oneMinusCos - axisX * s,

        axisZ * axisX * oneMinusCos - axisY * s,
        axisZ * axisY * oneMinusCos + axisX * s,
        c + axisZ * axisZ * oneMinusCos
    )

    multiply3x3(delta, rotationMatrix).copyInto(rotationMatrix)
}

private fun multiply3x3(a: FloatArray, b: FloatArray): FloatArray {
    val result = FloatArray(9)
    for (row in 0 until 3) {
        for (col in 0 until 3) {
            var sum = 0f
            for (k in 0 until 3) {
                sum += a[row * 3 + k] * b[k * 3 + col]
            }
            result[row * 3 + col] = sum
        }
    }
    return result
}
