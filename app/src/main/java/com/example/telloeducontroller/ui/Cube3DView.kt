package com.example.telloeducontroller.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color

private data class Vec3(val x: Float, val y: Float, val z: Float)

private val cubeVertices = listOf(
    Vec3(-1f, -1f, -1f), Vec3(1f, -1f, -1f), Vec3(1f, 1f, -1f), Vec3(-1f, 1f, -1f),
    Vec3(-1f, -1f, 1f), Vec3(1f, -1f, 1f), Vec3(1f, 1f, 1f), Vec3(-1f, 1f, 1f)
)

private val cubeEdges = listOf(
    0 to 1, 1 to 2, 2 to 3, 3 to 0,
    4 to 5, 5 to 6, 6 to 7, 7 to 4,
    0 to 4, 1 to 5, 2 to 6, 3 to 7
)

private val edgeColors = listOf(
    Color(0xFFEF5350), Color(0xFFEF5350), Color(0xFFEF5350), Color(0xFFEF5350),
    Color(0xFF42A5F5), Color(0xFF42A5F5), Color(0xFF42A5F5), Color(0xFF42A5F5),
    Color(0xFF66BB6A), Color(0xFF66BB6A), Color(0xFF66BB6A), Color(0xFF66BB6A)
)

/** Dessine un cube en fil de fer orienté selon [rotationMatrix] (matrice 3x3 row-major). */
@Composable
fun Cube3DView(rotationMatrix: FloatArray, modifier: Modifier = Modifier) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
    ) {
        val rotated = cubeVertices.map { rotate(it, rotationMatrix) }

        val focalLength = 4f
        val scale = size.minDimension / 4f
        val center = Offset(size.width / 2f, size.height / 2f)

        val projected = rotated.map { v ->
            val perspective = focalLength / (focalLength + v.z)
            Offset(
                center.x + v.x * scale * perspective,
                center.y - v.y * scale * perspective
            )
        }

        cubeEdges.forEachIndexed { index, (start, end) ->
            drawLine(
                color = edgeColors[index],
                start = projected[start],
                end = projected[end],
                strokeWidth = 5f
            )
        }
    }
}

private fun rotate(v: Vec3, m: FloatArray): Vec3 = Vec3(
    x = m[0] * v.x + m[1] * v.y + m[2] * v.z,
    y = m[3] * v.x + m[4] * v.y + m[5] * v.z,
    z = m[6] * v.x + m[7] * v.y + m[8] * v.z
)
