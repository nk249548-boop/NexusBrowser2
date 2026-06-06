package com.nexus.browser

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.nexus.browser.databinding.VideoQualityItemBinding

/**
 * RecyclerView adapter for displaying video quality options
 * @param qualities List of VideoQuality items to display
 * @param onQualitySelected Callback when a quality is selected
 */
class VideoQualityAdapter(
    private val qualities: List<VideoQuality>,
    private val onQualitySelected: (VideoQuality) -> Unit
) : RecyclerView.Adapter<VideoQualityViewHolder>() {

    private var selectedPosition = 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoQualityViewHolder {
        val binding = VideoQualityItemBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return VideoQualityViewHolder(binding) { position ->
            updateSelection(position)
        }
    }

    override fun onBindViewHolder(holder: VideoQualityViewHolder, position: Int) {
        holder.bind(qualities[position], position == selectedPosition)
    }

    override fun getItemCount(): Int = qualities.size

    fun setSelectedQuality(quality: VideoQuality) {
        val newPosition = qualities.indexOfFirst { it.id == quality.id }
        if (newPosition != -1 && newPosition != selectedPosition) {
            val oldPosition = selectedPosition
            selectedPosition = newPosition
            notifyItemChanged(oldPosition)
            notifyItemChanged(newPosition)
        }
    }

    private fun updateSelection(position: Int) {
        if (position == RecyclerView.NO_POSITION) return
        val oldPosition = selectedPosition
        selectedPosition = position
        notifyItemChanged(oldPosition)
        notifyItemChanged(position)
        onQualitySelected(qualities[position])
    }
}

/**
 * ViewHolder for individual video quality items
 */
class VideoQualityViewHolder(
    private val binding: VideoQualityItemBinding,
    private val onItemClick: (Int) -> Unit
) : RecyclerView.ViewHolder(binding.root) {

    init {
        binding.root.setOnClickListener {
            val pos = bindingAdapterPosition  // FIX: adapterPosition ki jagah bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                onItemClick(pos)
            }
        }
        binding.radioQuality.setOnClickListener {
            val pos = bindingAdapterPosition  // FIX: adapterPosition ki jagah bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                onItemClick(pos)
            }
        }
    }

    fun bind(quality: VideoQuality, isSelected: Boolean) {
        binding.apply {
            tvQualityLabel.text = quality.label
            tvQualityDescription.text = quality.description
            tvBandwidth.text = quality.bandwidthMbps
            radioQuality.isChecked = isSelected
            // RadioButton clickable false rakho taaki duplicate trigger na ho
            radioQuality.isClickable = false
        }
    }
}
