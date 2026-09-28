package com.example.filteredcam

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
//import com.google.common.collect.ImmutableList
import java.io.File

@UnstableApi
object VideoFilterProcessor {

    fun applyFilterToVideo(
        context: Context,
        sourceUri: Uri,
        colorMatrix: android.graphics.ColorMatrix,
        onSuccess: (Uri) -> Unit,
        onError: (String) -> Unit
    ) {
        val outputFile = File(context.cacheDir, "filtered_${System.currentTimeMillis()}.mp4")

        val rgbMatrix = androidColorMatrixToRgbMatrix(colorMatrix)

        val editedMediaItem = EditedMediaItem.Builder(MediaItem.fromUri(sourceUri))
            .setEffects(
                Effects(
                    /* audioProcessors= */ emptyList(),
                    /* videoEffects= */ listOf(rgbMatrix)
                )
            )
            .build()

        val transformer = Transformer.Builder(context)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    val savedUri = copyFileToGalleryVideos(context, outputFile)
                    if (savedUri != null) {
                        onSuccess(savedUri)
                    } else {
                        onError("Failed to save filtered video to gallery")
                    }
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    onError(exportException.message ?: "Unknown export error")
                }
            })
            .build()

        transformer.start(editedMediaItem, outputFile.absolutePath)
    }

    private fun copyFileToGalleryVideos(context: Context, file: File): Uri? {
        val name = "FilteredVideo_${System.currentTimeMillis()}.mp4"

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/FilteredCam")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val uri = context.contentResolver.insert(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ) ?: return null

        try {
            context.contentResolver.openOutputStream(uri)?.use { output ->
                file.inputStream().use { input ->
                    input.copyTo(output)
                }
            } ?: throw Exception("Could not open output stream")

            // Mark as complete so it becomes visible/indexed
            val doneValues = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            context.contentResolver.update(uri, doneValues, null, null)

            file.delete()
            return uri

        } catch (e: Exception) {
            e.printStackTrace()
            // Clean up the broken MediaStore entry if the copy failed
            context.contentResolver.delete(uri, null, null)
            return null
        }
    }}