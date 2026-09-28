package com.example.filteredcam

import android.annotation.SuppressLint
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide

data class MediaEntry(val uri: Uri, val isVideo: Boolean, val dateAdded: Long)

class PhotoAdapter(
    private var items: List<MediaEntry>,
    private val onItemClick: (Int) -> Unit
) : RecyclerView.Adapter<PhotoAdapter.PhotoViewHolder>() {

    class PhotoViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val imageView: ImageView = view.findViewById(R.id.photoThumbnail)
        val videoBadge: ImageView = view.findViewById(R.id.videoBadge)
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
        holder.videoBadge.visibility = if (entry.isVideo) View.VISIBLE else View.GONE
        holder.itemView.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onItemClick(pos)
        }
    }

    override fun getItemCount(): Int = items.size
}