package com.example.filteredcam

import android.graphics.ColorMatrix
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class ColorAdjustments {
    var brightness = 0f   // -80..80, added to each channel (0-255 scale)
    var contrast = 1f     // 0.5..1.5, 1 = unchanged
    var saturation = 1f   // 0..2, 1 = unchanged
    var hue = 0f          // -180..180 degrees
    var warmth = 0f       // -100..100, positive = warmer

    val isDefault: Boolean
        get() = abs(brightness) < 0.01f &&
                abs(contrast - 1f) < 0.001f &&
                abs(saturation - 1f) < 0.001f &&
                abs(hue) < 0.01f &&
                abs(warmth) < 0.01f

    fun toColorMatrix(): ColorMatrix {
        val m = ColorMatrix()

        // 1. Saturation
        m.postConcat(ColorMatrix().apply { setSaturation(saturation) })

        // 2. Hue rotation
        m.postConcat(hueMatrix(hue))

        // 3. Contrast around mid-gray (128), plus brightness offset
        val c = contrast
        val t = (1f - c) * 128f + brightness
        m.postConcat(
            ColorMatrix(
                floatArrayOf(
                    c, 0f, 0f, 0f, t,
                    0f, c, 0f, 0f, t,
                    0f, 0f, c, 0f, t,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )

        // 4. Warmth: push red up and blue down (or the reverse)
        val w = warmth / 100f * 40f
        m.postConcat(
            ColorMatrix(
                floatArrayOf(
                    1f, 0f, 0f, 0f, w,
                    0f, 1f, 0f, 0f, 0f,
                    0f, 0f, 1f, 0f, -w,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )
        return m
    }

    // Standard luminance-preserving hue rotation (same formula as SVG/CSS hue-rotate)
    private fun hueMatrix(degrees: Float): ColorMatrix {
        val rad = Math.toRadians(degrees.toDouble())
        val a = cos(rad).toFloat()
        val b = sin(rad).toFloat()
        return ColorMatrix(
            floatArrayOf(
                0.213f + a * 0.787f - b * 0.213f, 0.715f - a * 0.715f - b * 0.715f, 0.072f - a * 0.072f + b * 0.928f, 0f, 0f,
                0.213f - a * 0.213f + b * 0.143f, 0.715f + a * 0.285f + b * 0.140f, 0.072f - a * 0.072f - b * 0.283f, 0f, 0f,
                0.213f - a * 0.213f - b * 0.787f, 0.715f - a * 0.715f + b * 0.715f, 0.072f + a * 0.928f + b * 0.072f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )
        )
    }
}