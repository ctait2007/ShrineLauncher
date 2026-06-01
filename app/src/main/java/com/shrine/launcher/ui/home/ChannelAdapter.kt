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
import com.shrine.launcher.data.model.ChannelType
import com.shrine.launcher.data.model.TvContent

/**
 * Adapter for CHANNEL rows (Continue Watching, Watch Next, New For You).
 * Displays TvContent cards with artwork, title, subtitle and progress bar.
 */
class ChannelAdapter(
    private val displayMode: CardDisplayMode = CardDisplayMode.BANNER,
    private val cornerRadiusPercent: Int = 50,
    private val iconSizeDp: Int = 88,
    private val onClick: (TvContent) -> Unit,
    private val onLongClick: (TvContent) -> Unit,
    private val onFocused: () -> Unit,
    private val onLeftFromFirst: (() -> Unit)? = null,
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
            tvTitle.visibility    = View.INVISIBLE
            tvSubtitle.visibility = View.INVISIBLE
            focusOverlay.visibility = View.GONE

            // Size the card
            val density = cardRoot.resources.displayMetrics.density
            val heightPx = (iconSizeDp * density).toInt()
            val widthPx  = ((iconSizeDp * 16f / 9f) * density).toInt()
            val params = cardRoot.layoutParams
            params.height = heightPx
            params.width  = widthPx
            cardRoot.layoutParams = params
            cardRoot.requestLayout()

            // Clip only the artwork to rounded corners so the progress bar and focus
            // overlay aren't cut off by the outline.
            val radius = heightPx * (cornerRadiusPercent / 100f) * 0.5f
            val roundedBg = android.graphics.drawable.GradientDrawable().apply {
                shape        = android.graphics.drawable.GradientDrawable.RECTANGLE
                setColor(0x011E1E1E.toInt())
                cornerRadius = radius
            }
            ivArtwork.background      = roundedBg
            ivArtwork.clipToOutline   = true
            ivArtwork.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            // cardRoot must NOT clip children — progress bar and focus overlay sit inside it
            cardRoot.background  = null
            cardRoot.clipToOutline = false

            // Diagnose progress values so we can confirm the cursor columns are correct
            android.util.Log.d("ChannelAdapter",
                "bind: id=${content.id} title=${content.title} " +
                "progressMs=${content.progressMs} durationMs=${content.durationMs} " +
                "progressPercent=${content.progressPercent}")

            // Progress bar: show whenever we have a playback position, or for any
            // Continue Watching content (which by definition has been started).
            when {
                content.durationMs > 0 && content.progressMs > 0 -> {
                    progressBar.visibility = View.VISIBLE
                    progressBar.progress   = content.progressPercent
                }
                content.progressMs > 0 -> {
                    // Known position but unknown duration — show minimum 10%
                    progressBar.visibility = View.VISIBLE
                    progressBar.progress   = 10
                }
                content.channelType == ChannelType.CONTINUE_WATCHING -> {
                    // Content is in Continue Watching: user was watching it even if
                    // the app didn't populate COLUMN_LAST_PLAYBACK_POSITION_TIME_MILLIS
                    progressBar.visibility = View.VISIBLE
                    progressBar.progress   = 10
                }
                else -> progressBar.visibility = View.GONE
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

            cardRoot.stateListAnimator = null

            cardRoot.setOnKeyListener { _, keyCode, event ->
                if (event.action != android.view.KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (keyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (adapterPosition == 0) { onLeftFromFirst?.invoke(); true } else false
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        val rv = itemView.parent as? RecyclerView
                        val lm = rv?.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager
                        val next = adapterPosition + 1
                        if (rv != null && lm != null && next < (rv.adapter?.itemCount ?: 0)) {
                            lm.findViewByPosition(next)?.requestFocus()
                                ?: rv.smoothScrollToPosition(next)
                        }
                        true
                    }
                    else -> false
                }
            }

            cardRoot.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
                if (hasFocus && v.hasWindowFocus()) onFocused()

                // Draw focus border via focusOverlay (rendered ON TOP of artwork).
                // Drawing on cardRoot.background is hidden under ivArtwork (match_parent).
                if (hasFocus) {
                    focusOverlay.background = android.graphics.drawable.GradientDrawable().apply {
                        shape        = android.graphics.drawable.GradientDrawable.RECTANGLE
                        setColor(0x00000000)
                        cornerRadius = radius
                        setStroke((3 * v.resources.displayMetrics.density).toInt(), 0xFFFFFFFF.toInt())
                    }
                    focusOverlay.visibility = View.VISIBLE
                } else {
                    focusOverlay.visibility = View.GONE
                    focusOverlay.background = null
                }

                tvTitle.visibility    = if (hasFocus) View.VISIBLE else View.INVISIBLE
                tvSubtitle.visibility = if (hasFocus) View.VISIBLE else View.INVISIBLE
            }
        }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<TvContent>() {
            override fun areItemsTheSame(a: TvContent, b: TvContent) = a.id == b.id
            override fun areContentsTheSame(a: TvContent, b: TvContent) = a == b
        }
    }
}
