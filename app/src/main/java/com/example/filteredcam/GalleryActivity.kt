package com.example.filteredcam

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

class GalleryActivity : AppCompatActivity() {

    private lateinit var adapter: PhotoAdapter
    private lateinit var photoGrid: RecyclerView
    private lateinit var emptyState: View
    private lateinit var countText: TextView

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refresh()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gallery)

        // Light status bar icons, since the screen background is dark
        WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightStatusBars = false

        photoGrid = findViewById(R.id.photoGrid)
        emptyState = findViewById(R.id.emptyState)
        countText = findViewById(R.id.countText)

        findViewById<ImageButton>(R.id.backButton).setOnClickListener { finish() }
        findViewById<TextView>(R.id.emptyActionButton).setOnClickListener { finish() }

        photoGrid.layoutManager = GridLayoutManager(this, 3)
        adapter = PhotoAdapter(emptyList()) { index ->
            startActivity(
                Intent(this, MediaViewerActivity::class.java).putExtra("startIndex", index)
            )
        }
        photoGrid.adapter = adapter

        val missing = mediaPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val media = MediaRepository.loadAppMedia(this)
        adapter.submit(media)

        val isEmpty = media.isEmpty()
        emptyState.visibility = if (isEmpty) View.VISIBLE else View.GONE
        photoGrid.visibility = if (isEmpty) View.GONE else View.VISIBLE
        countText.visibility = if (isEmpty) View.GONE else View.VISIBLE

        val photos = media.count { !it.isVideo }
        val videos = media.size - photos
        countText.text = buildList {
            if (photos > 0) add(if (photos == 1) "1 photo" else "$photos photos")
            if (videos > 0) add(if (videos == 1) "1 video" else "$videos videos")
        }.joinToString(" · ")
    }

    private fun mediaPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
}