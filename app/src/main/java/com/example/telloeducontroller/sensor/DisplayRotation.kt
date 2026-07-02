package com.example.telloeducontroller.sensor

import android.content.Context
import android.os.Build
import android.view.Surface
import android.view.WindowManager

/** Rotation actuelle de l'écran (Surface.ROTATION_0/90/180/270), utilisée pour remapper les axes des capteurs. */
internal fun currentDisplayRotation(context: Context): Int {
    val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        context.display
    } else {
        @Suppress("DEPRECATION")
        (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
    }
    return display?.rotation ?: Surface.ROTATION_0
}
