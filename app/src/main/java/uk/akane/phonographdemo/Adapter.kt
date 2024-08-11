package uk.akane.phonographdemo

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.squareup.picasso.Picasso

class Adapter : RecyclerView.Adapter<Adapter.ViewHolder>() {
    private val songList: MutableList<MediaItem> = mutableListOf()

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val cover: ImageView = view.findViewById(R.id.cover)
        val title: TextView = view.findViewById(R.id.title)
        val subtitle: TextView = view.findViewById(R.id.subtitle)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.list_item, parent, false)
        )

    override fun getItemCount(): Int = songList.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        Picasso.get()
            .load(songList[position].mediaMetadata.artworkUri)
            .into(holder.cover)
        holder.title.text = songList[position].mediaMetadata.title
        holder.subtitle.text = songList[position].mediaMetadata.artist
    }

    fun updateList(newList: MutableList<MediaItem>) {
        val diffResult = DiffUtil.calculateDiff(SongDiffCallback(songList, newList))
        songList.clear()
        songList.addAll(newList)
        diffResult.dispatchUpdatesTo(this)
    }

    private class SongDiffCallback(
        private val oldList: MutableList<MediaItem>,
        private val newList: MutableList<MediaItem>,
    ) : DiffUtil.Callback() {
        override fun getOldListSize() = oldList.size

        override fun getNewListSize() = newList.size

        override fun areItemsTheSame(
            oldItemPosition: Int,
            newItemPosition: Int,
        ) = oldList[oldItemPosition].mediaId == newList[newItemPosition].mediaId

        override fun areContentsTheSame(
            oldItemPosition: Int,
            newItemPosition: Int,
        ) = oldList[oldItemPosition].mediaMetadata == newList[newItemPosition].mediaMetadata
    }
}