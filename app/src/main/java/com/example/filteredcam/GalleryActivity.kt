package com.example.filteredcam

import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

class GalleryActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gallery)

        val photoGrid = findViewById<RecyclerView>(R.id.photoGrid)
        photoGrid.layoutManager = GridLayoutManager(this, 3)

        val photoUris = loadAppPhotos()

        photoGrid.adapter = PhotoAdapter(photoUris) { uri ->
            val intent = android.content.Intent(this, PhotoViewActivity::class.java)
            intent.putExtra("photoUri", uri.toString())
            startActivity(intent)
        }
    }

    private fun loadAppPhotos(): List<Uri> {
        val uris = mutableListOf<Uri>()

        val projection = arrayOf(MediaStore.Images.Media._ID)
        val selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
        val selectionArgs = arrayOf("%FilteredCam%")
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            sortOrder
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val uri = android.content.ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    id
                )
                uris.add(uri)
            }
        }

        return uris
    }
}