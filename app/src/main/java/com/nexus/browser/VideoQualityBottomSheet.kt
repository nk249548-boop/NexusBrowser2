package com.nexus.browser

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.nexus.browser.databinding.VideoQualitySheetBinding

/**
 * Bottom Sheet — Video Quality Selector
 *
 * BUG FIX: Lambda constructor ki jagah interface use kiya —
 * screen rotation pe lambda capture crash karta tha.
 *
 * BUG FIX: VideoQuality fields updated to use unified data class —
 * pehle VideoQuality.url aur VideoQuality.bandwidth int use ho rahe the
 * jo sirf M3u8Parser ki deprecated class mein the.
 *
 * Usage:
 *   val sheet = VideoQualityBottomSheet.newInstance("720p")
 *   sheet.listener = object : VideoQualityBottomSheet.QualitySelectedListener {
 *       override fun onQualitySelected(quality: VideoQuality) { ... }
 *   }
 *   sheet.show(supportFragmentManager, "quality")
 */
class VideoQualityBottomSheet : BottomSheetDialogFragment() {

    interface QualitySelectedListener {
        fun onQualitySelected(quality: VideoQuality)
    }

    var listener: QualitySelectedListener? = null

    private var _binding: VideoQualitySheetBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: VideoQualityAdapter
    private var currentQualityId = "auto"

    companion object {
        private const val ARG_QUALITY_ID = "current_quality_id"

        fun newInstance(currentQualityId: String = "auto") = VideoQualityBottomSheet().apply {
            arguments = Bundle().apply { putString(ARG_QUALITY_ID, currentQualityId) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentQualityId = arguments?.getString(ARG_QUALITY_ID) ?: "auto"
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = VideoQualitySheetBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
    }

    private fun setupRecyclerView() {
        val qualities = buildQualityOptions()

        adapter = VideoQualityAdapter(qualities) { selected ->
            updateCurrentDisplay(selected)
            listener?.onQualitySelected(selected)
        }

        binding.rvResolutions.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@VideoQualityBottomSheet.adapter
        }

        qualities.find { it.id == currentQualityId }?.let {
            adapter.setSelectedQuality(it)
            updateCurrentDisplay(it)
        }
    }

    fun setCurrentQuality(quality: VideoQuality) {
        currentQualityId = quality.id
        if (::adapter.isInitialized) {
            adapter.setSelectedQuality(quality)
            updateCurrentDisplay(quality)
        }
    }

    private fun updateCurrentDisplay(quality: VideoQuality) {
        // BUG FIX: VideoQuality.label use karo — 'url' field nahi hai unified class mein
        binding.tvCurrentQuality.text = "Current: ${quality.label}"
    }

    /**
     * BUG FIX: VideoQuality constructor — sirf unified data class ke fields use ho rahe hain.
     * bandwidthBps aur streamUrl default values pe hain (0 aur "") because these are
     * UI display options, not parsed HLS streams.
     */
    private fun buildQualityOptions() = listOf(
        VideoQuality(
            id = "auto", label = "Auto (Adaptive)", resolution = "Variable",
            description = "Network ke hisaab se automatically adjust hoga",
            bandwidthMbps = "Variable", isAutomatic = true
        ),
        VideoQuality(
            id = "144p", label = "144p (Very Low)", resolution = "256x144",
            description = "Data saver mode — bahut slow connections ke liye",
            bandwidthMbps = "< 0.3 Mbps"
        ),
        VideoQuality(
            id = "360p", label = "360p (SD)", resolution = "640x360",
            description = "Mobile streaming ke liye achha",
            bandwidthMbps = "0.5-1 Mbps"
        ),
        VideoQuality(
            id = "480p", label = "480p (SD)", resolution = "854x480",
            description = "Quality aur data ka balance",
            bandwidthMbps = "1-2 Mbps"
        ),
        VideoQuality(
            id = "720p", label = "720p (HD)", resolution = "1280x720",
            description = "Achhi quality, moderate data usage",
            bandwidthMbps = "2-3 Mbps"
        ),
        VideoQuality(
            id = "1080p", label = "1080p (Full HD)", resolution = "1920x1080",
            description = "High quality — WiFi recommended",
            bandwidthMbps = "4-5 Mbps"
        ),
        VideoQuality(
            id = "1440p", label = "1440p (QHD)", resolution = "2560x1440",
            description = "Bahut high quality — WiFi required",
            bandwidthMbps = "8-10 Mbps"
        ),
        VideoQuality(
            id = "2160p", label = "2160p (4K UHD)", resolution = "3840x2160",
            description = "Ultra HD — sabse zyada data",
            bandwidthMbps = "15-25 Mbps"
        )
    )

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
