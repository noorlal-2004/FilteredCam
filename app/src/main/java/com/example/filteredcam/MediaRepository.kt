package com.example.filteredcam

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log

object MediaRepository {

    fun loadAppMedia(context: Context): List<MediaEntry> {
        val results = mutableListOf<MediaEntry>()
        val photos = query(context, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false, results)
        val videos = query(context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, results)
        Log.d("MediaRepo", "found photos=$photos, videos=$videos")
        return results.sortedByDescending { it.dateAdded }
    }

    private fun query(
        context: Context,
        collection: Uri,
        isVideo: Boolean,
        out: MutableList<MediaEntry>
    ): Int {
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_ADDED)
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
        val args = arrayOf("%FilteredCam%")
        var count = 0

        context.contentResolver.query(collection, projection, selection, args, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            while (cursor.moveToNext()) {
                val uri = ContentUris.withAppendedId(collection, cursor.getLong(idCol))
                out.add(MediaEntry(uri, isVideo, cursor.getLong(dateCol)))
                count++
            }
        }
        return count
    }
}