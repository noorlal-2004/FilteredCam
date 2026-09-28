package com.example.filteredcam

import android.annotation.SuppressLint
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import java.util.Locale

data class MediaEntry(
    val uri: Uri,
    val isVideo: Boolean,
    val dateAdded: Long,
    val durationMs: Long = 0L
)

class PhotoAdapter(
    private var items: List<MediaEntry>,
    private val onItemClick: (Int) -> Unit
) : RecyclerView.Adapter<PhotoAdapter.PhotoViewHolder>() {

    class PhotoViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val imageView: ImageView = view.findViewById(R.id.photoThumbnail)
        val videoBadge: TextView = view.findViewById(R.id.videoBadge)
    }

    @SuppressLint("NotifyDataSetChanged")
    fun submit(newItems: List<MediaEntry>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_photo, parent, false)
        return PhotoViewHolder(view)
    }

    override fun onBindViewHolder(holder: PhotoViewHolder, position: Int) {
        val entry = items[position]
        Glide.with(holder.itemView).load(entry.uri).centerCrop().into(holder.imageView)

        if (entry.isVideo) {
            holder.videoBadge.text = "▶ ${formatDuration(entry.durationMs)}"
            holder.videoBadge.visibility = View.VISIBLE
        } else {
            holder.videoBadge.visibility = View.GONE
        }

        holder.itemView.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onItemClick(pos)
        }
    }

    private fun formatDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        return String.format(Locale.US, "%d:%02d", totalSeconds / 60, totalSeconds % 60)
    }

    override fun getItemCount(): Int = items.size
}