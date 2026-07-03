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

/** Attitude du téléphone en degrés, relative au point neutre défini par [AttitudeHandle.center]. */
data class Attitude(
    val rollDeg: Float = 0f,
    val pitchDeg: Float = 0f,
    val yawDeg: Float = 0f,
    val sensorAvailable: Boolean = true
)

/** Poignée exposée à l'UI pour lire l'attitude et recentrer la position neutre de pilotage. */
class AttitudeHandle(val attitude: State<Attitude>, val center: () -> Unit)

/**
 * Utilise TYPE_GAME_ROTATION_VECTOR (fusion gyroscope + accéléromètre, sans magnétomètre) plutôt
 * qu'une intégration brute du gyroscope : le roulis et le tangage restent référencés sur la
 * gravité et ne dérivent donc pas au cours du vol, contrairement à une simple intégration de la
 * vitesse angulaire (utilisée pour la démo du cube). Le lacet n'a pas de référence absolue (pas de
 * boussole) mais ce n'est pas gênant puisqu'il n'est utilisé qu'en relatif, depuis le point centré
 * par l'utilisateur.
 */
@Composable
fun rememberAttitudeState(): AttitudeHandle {
    val context = LocalContext.current
    val attitude = remember { mutableStateOf(Attitude()) }
    val centerRequested = remember { AttitudeBooleanRef() }

    DisposableEffect(context) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)

        if (rotationSensor == null) {
            attitude.value = Attitude(sensorAvailable = false)
            return@DisposableEffect onDispose {}
        }

        val rotation = currentDisplayRotation(context)
        val rotationMatrix = FloatArray(9)
        val screenMatrix = FloatArray(9)
        val verticalMatrix = FloatArray(9)
        val orientationRad = FloatArray(3)

        var rollOffset = 0f
        var pitchOffset = 0f
        var yawOffset = 0f

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)

                val (axisX, axisY) = when (rotation) {
                    Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
                    Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
                    Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
                    else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
                }
                SensorManager.remapCoordinateSystem(rotationMatrix, axisX, axisY, screenMatrix)

                // getOrientation() suppose l'appareil posé à plat, écran vers le ciel : dans cette
                // convention, tenir le téléphone verticalement (écran face à soi, comme on le fait
                // pour piloter en regardant l'écran) place le tangage proche de 90° et fait entrer
                // le calcul en gimbal lock (roulis et lacet se mélangent, deviennent erratiques). On
                // remappe l'axe Z (perpendiculaire à l'écran) sur Y pour que "à plat" corresponde à
                // cette prise en main verticale, ce qui déplace la singularité loin de la zone de
                // pilotage normale.
                SensorManager.remapCoordinateSystem(screenMatrix, SensorManager.AXIS_X, SensorManager.AXIS_Z, verticalMatrix)
                SensorManager.getOrientation(verticalMatrix, orientationRad)

                val yaw = Math.toDegrees(orientationRad[0].toDouble()).toFloat()
                val pitch = Math.toDegrees(orientationRad[1].toDouble()).toFloat()
                val roll = Math.toDegrees(orientationRad[2].toDouble()).toFloat()

                if (centerRequested.value) {
                    centerRequested.value = false
                    rollOffset = roll
                    pitchOffset = pitch
                    yawOffset = yaw
                }

                attitude.value = Attitude(
                    rollDeg = roll - rollOffset,
                    pitchDeg = pitch - pitchOffset,
                    yawDeg = wrapDegrees(yaw - yawOffset)
                )
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        sensorManager.registerListener(listener, rotationSensor, SensorManager.SENSOR_DELAY_GAME)

        onDispose {
            sensorManager.unregisterListener(listener)
        }
    }

    return AttitudeHandle(attitude) { centerRequested.value = true }
}

private class AttitudeBooleanRef(var value: Boolean = false)

private fun wrapDegrees(angle: Float): Float {
    var a = angle
    while (a > 180f) a -= 360f
    while (a < -180f) a += 360f
    return a
}
