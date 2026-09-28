package com.example.filteredcam

import android.app.RecoverableSecurityException
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager2.widget.ViewPager2

class MediaViewerActivity : AppCompatActivity() {

    private lateinit var pager: ViewPager2
    private lateinit var adapter: MediaPagerAdapter
    private lateinit var counterText: TextView
    private lateinit var bottomBar: View

    private val items = mutableListOf<MediaEntry>()
    private var pendingDeleteEntry: MediaEntry? = null

    // Used when the system must confirm deleting a file this install doesn't own
    private val deleteRequestLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                pendingDeleteEntry?.let { entry ->
                    // Android 11+ deletes on approval; Android 10 needs a retry
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                        contentResolver.delete(entry.uri, null, null)
                    }
                    removeFromList(entry)
                }
            }
            pendingDeleteEntry = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_media_viewer)

        pager = findViewById(R.id.mediaPager)
        counterText = findViewById(R.id.counterText)
        bottomBar = findViewById(R.id.bottomBar)

        items.addAll(MediaRepository.loadAppMedia(this))
        if (items.isEmpty()) {
            finish()
            return
        }

        adapter = MediaPagerAdapter(items) { toggleBars() }
        pager.adapter = adapter
        pager.setCurrentItem(intent.getIntExtra("startIndex", 0), false)
        updateCounter()

        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                adapter.stopPlayback()
                updateCounter()
            }
        })

        findViewById<ImageButton>(R.id.shareButton).setOnClickListener { shareCurrent() }
        findViewById<ImageButton>(R.id.deleteButton).setOnClickListener { confirmDelete() }
    }

    private fun currentEntry(): MediaEntry? = items.getOrNull(pager.currentItem)

    private fun updateCounter() {
        counterText.text = "${pager.currentItem + 1} / ${items.size}"
    }

    private fun toggleBars() {
        val show = bottomBar.visibility != View.VISIBLE
        bottomBar.visibility = if (show) View.VISIBLE else View.GONE
        counterText.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun shareCurrent() {
        val entry = currentEntry() ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = if (entry.isVideo) "video/*" else "image/*"
            putExtra(Intent.EXTRA_STREAM, entry.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share via"))
    }

    private fun confirmDelete() {
        val entry = currentEntry() ?: return
        AlertDialog.Builder(this)
            .setTitle(if (entry.isVideo) "Delete this video?" else "Delete this photo?")
            .setMessage("This can't be undone.")
            .setPositiveButton("Delete") { _, _ -> deleteEntry(entry) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteEntry(entry: MediaEntry) {
        try {
            val rows = contentResolver.delete(entry.uri, null, null)
            if (rows > 0) {
                removeFromList(entry)
            } else {
                Toast.makeText(this, "Couldn't delete this file", Toast.LENGTH_SHORT).show()
            }
        } catch (e: SecurityException) {
            requestSystemDelete(entry, e)
        }
    }

    private fun requestSystemDelete(entry: MediaEntry, e: SecurityException) {
        val sender = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                MediaStore.createDeleteRequest(contentResolver, listOf(entry.uri)).intentSender
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && e is RecoverableSecurityException ->
                e.userAction.actionIntent.intentSender
            else -> null
        }

        if (sender != null) {
            pendingDeleteEntry = entry
            deleteRequestLauncher.launch(IntentSenderRequest.Builder(sender).build())
        } else {
            Toast.makeText(this, "Couldn't delete this file", Toast.LENGTH_SHORT).show()
        }
    }

    private fun removeFromList(entry: MediaEntry) {
        val index = items.indexOf(entry)
        if (index == -1) return

        adapter.stopPlayback()
        items.removeAt(index)

        if (items.isEmpty()) {
            finish()
            return
        }
        adapter.notifyItemRemoved(index)
        updateCounter()
        Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show()
    }
}