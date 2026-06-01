package com.shrine.launcher.ui.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.shrine.launcher.R
import com.shrine.launcher.data.model.CardDisplayMode
import com.shrine.launcher.data.model.TvContent

/**
 * Adapter for CHANNEL rows (Continue Watching, Watch Next, New For You).
 * Displays TvContent cards with artwork, title, subtitle and progress bar.
 */
class ChannelAdapter(
    private val displayMode: CardDisplayMode = CardDisplayMode.BANNER,
    private val cornerRadiusPercent: Int = 50,
    private val onClick: (TvContent) -> Unit,
    private val onLongClick: (TvContent) -> Unit,
    private val onFocused: () -> Unit
) : ListAdapter<TvContent, ChannelAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_channel_card, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) =
        holder.bind(getItem(position))

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        private val cardRoot:    View        = view.findViewById(R.id.channelCardRoot)
        private val ivArtwork:   ImageView   = view.findViewById(R.id.ivChannelArtwork)
        private val tvTitle:     TextView    = view.findViewById(R.id.tvChannelTitle)
        private val tvSubtitle:  TextView    = view.findViewById(R.id.tvChannelSubtitle)
        private val progressBar: ProgressBar = view.findViewById(R.id.pbChannelProgress)
        private val focusOverlay: View       = view.findViewById(R.id.channelFocusOverlay)

        fun bind(content: TvContent) {
            tvTitle.text    = content.title
            tvSubtitle.text = content.subtitle
            applyCardBg(cardRoot, focused = false)

            if (content.durationMs > 0) {
                progressBar.visibility = View.VISIBLE
                progressBar.progress   = content.progressPercent
            } else {
                progressBar.visibility = View.GONE
            }

            if (!content.artworkUri.isNullOrBlank()) {
                Glide.with(ivArtwork)
                    .load(content.artworkUri)
                    .centerCrop()
                    .placeholder(R.drawable.bg_card_placeholder)
                    .into(ivArtwork)
            } else {
                ivArtwork.setImageResource(R.drawable.bg_card_placeholder)
            }

            cardRoot.setOnClickListener { onClick(content) }
            cardRoot.setOnLongClickListener { onLongClick(content); true }
            cardRoot.isFocusable = true
            cardRoot.isFocusableInTouchMode = false

            cardRoot.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
                if (hasFocus && v.hasWindowFocus()) onFocused()
                applyCardBg(v, focused = hasFocus)
                v.animate()
                    .translationZ(if (hasFocus) 8f else 0f)
                    .setDuration(120).start()
            }
        }
    }

    private fun applyCardBg(v: android.view.View, focused: Boolean) {
        val density   = v.resources.displayMetrics.density
        val cardPx    = 140 * density
        val radius    = cardPx * (cornerRadiusPercent / 100f) * 0.5f
        val strokePx  = if (focused) (3 * density).toInt() else 0
        val strokeCol = if (focused) 0xFFFFFFFF.toInt() else 0x00000000
        val bgColor   = 0xFF1E1E1E.toInt()
        val bg = android.graphics.drawable.GradientDrawable().apply {
            shape        = android.graphics.drawable.GradientDrawable.RECTANGLE
            setColor(bgColor)
            cornerRadius = radius
            setStroke(strokePx, strokeCol)
        }
        v.background      = bg
        v.clipToOutline   = true
        v.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<TvContent>() {
            override fun areItemsTheSame(a: TvContent, b: TvContent) = a.id == b.id
            override fun areContentsTheSame(a: TvContent, b: TvContent) = a == b
        }
    }
}
