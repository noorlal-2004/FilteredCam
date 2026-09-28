package com.example.filteredcam

import android.graphics.ColorMatrix
import androidx.media3.effect.RgbMatrix

fun androidColorMatrixToRgbMatrix(colorMatrix: ColorMatrix): RgbMatrix {
    val m = colorMatrix.array // 20 floats, row-major: [R, G, B, A, offset] per row

    // Build a 4x4 matrix, folding the 0-255 offset into a normalized (0-1) term
    // applied via the alpha channel (which is always 1.0 for opaque video frames)
    val rowMajor = FloatArray(16)
    for (row in 0 until 4) {
        rowMajor[row * 4 + 0] = m[row * 5 + 0]
        rowMajor[row * 4 + 1] = m[row * 5 + 1]
        rowMajor[row * 4 + 2] = m[row * 5 + 2]
        rowMajor[row * 4 + 3] = m[row * 5 + 3] + (m[row * 5 + 4] / 255f)
    }

    // OpenGL expects column-major order
    val columnMajor = FloatArray(16)
    for (row in 0 until 4) {
        for (col in 0 until 4) {
            columnMajor[col * 4 + row] = rowMajor[row * 4 + col]
        }
    }

    return object : RgbMatrix {
        override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray {
            return columnMajor
        }
    }
}