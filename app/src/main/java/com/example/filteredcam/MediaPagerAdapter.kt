package com.example.filteredcam

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.VideoView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide

class MediaPagerAdapter(
    private val items: List<MediaEntry>,
    private val onTap: () -> Unit
) : RecyclerView.Adapter<MediaPagerAdapter.PageHolder>() {

    class PageHolder(view: View) : RecyclerView.ViewHolder(view) {
        val image: ImageView = view.findViewById(R.id.pageImage)
        val video: VideoView = view.findViewById(R.id.pageVideo)
        val play: ImageView = view.findViewById(R.id.pagePlay)
    }

    private var playingHolder: PageHolder? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_media_page, parent, false)
        return PageHolder(view)
    }

    override fun onBindViewHolder(holder: PageHolder, position: Int) {
        val entry = items[position]
        resetHolder(holder)

        Glide.with(holder.itemView).load(entry.uri).into(holder.image)
        holder.play.visibility = if (entry.isVideo) View.VISIBLE else View.GONE

        holder.image.setOnClickListener { onTap() }
        holder.play.setOnClickListener { startVideo(holder, entry) }
    }

    private fun startVideo(holder: PageHolder, entry: MediaEntry) {
        stopPlayback()
        playingHolder = holder

        holder.image.visibility = View.INVISIBLE
        holder.play.visibility = View.GONE
        holder.video.visibility = View.VISIBLE

        holder.video.setVideoURI(entry.uri)
        holder.video.setOnCompletionListener {
            resetHolder(holder)
            holder.play.visibility = View.VISIBLE
            playingHolder = null
        }
        // Tap the playing video to pause/resume
        holder.video.setOnClickListener {
            if (holder.video.isPlaying) holder.video.pause() else holder.video.start()
        }
        holder.video.start()
    }

    /** Stops any playing video and restores its play button. Call when the page changes. */
    fun stopPlayback() {
        playingHolder?.let {
            resetHolder(it)
            it.play.visibility = View.VISIBLE
        }
        playingHolder = null
    }

    private fun resetHolder(holder: PageHolder) {
        holder.video.stopPlayback()
        holder.video.visibility = View.GONE
        holder.image.visibility = View.VISIBLE
    }

    override fun onViewRecycled(holder: PageHolder) {
        if (holder == playingHolder) playingHolder = null
        resetHolder(holder)
        super.onViewRecycled(holder)
    }

    override fun getItemCount(): Int = items.size
}