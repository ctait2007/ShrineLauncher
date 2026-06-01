package com.shrine.launcher.ui.widgets

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.shrine.launcher.R
import com.shrine.launcher.data.model.ContinueWatchingEntry

class ContinueWatchingWidgetView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val rvEntries: RecyclerView
    private val tvTitle: TextView
    private var onEntryClick: ((ContinueWatchingEntry) -> Unit)? = null
    private var onEntryRemove: ((ContinueWatchingEntry) -> Unit)? = null

    private val adapter = ContinueWatchingAdapter(
        onClick  = { entry -> onEntryClick?.invoke(entry) },
        onRemove = { entry -> onEntryRemove?.invoke(entry) }
    )

    init {
        LayoutInflater.from(context).inflate(R.layout.widget_continue_watching, this, true)
        tvTitle   = findViewById(R.id.tvContinueWatchingTitle)
        rvEntries = findViewById(R.id.rvContinueWatching)
        rvEntries.layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        rvEntries.adapter = adapter
    }

    fun setEntries(entries: List<ContinueWatchingEntry>) {
        adapter.submitList(entries)
        visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE
    }

    fun setOnEntryClickListener(listener: (ContinueWatchingEntry) -> Unit) {
        onEntryClick = listener
    }

    fun setOnEntryRemoveListener(listener: (ContinueWatchingEntry) -> Unit) {
        onEntryRemove = listener
    }
}

// ── Adapter ───────────────────────────────────────────────────────────────────

class ContinueWatchingAdapter(
    private val onClick: (ContinueWatchingEntry) -> Unit,
    private val onRemove: (ContinueWatchingEntry) -> Unit
) : ListAdapter<ContinueWatchingEntry, ContinueWatchingAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_continue_watching_card, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) =
        holder.bind(getItem(position))

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        private val ivArtwork:  ImageView  = view.findViewById(R.id.ivCwArtwork)
        private val tvTitle:    TextView   = view.findViewById(R.id.tvCwTitle)
        private val tvSubtitle: TextView   = view.findViewById(R.id.tvCwSubtitle)
        private val progressBar: ProgressBar = view.findViewById(R.id.pbCwProgress)
        private val btnRemove:  View       = view.findViewById(R.id.btnCwRemove)
        private val cardRoot:   View       = view.findViewById(R.id.cwCardRoot)

        fun bind(entry: ContinueWatchingEntry) {
            tvTitle.text    = entry.title
            tvSubtitle.text = entry.subtitle
            progressBar.progress = entry.progressPercent
            progressBar.visibility = if (entry.durationMs > 0) View.VISIBLE else View.GONE

            if (!entry.artworkUri.isNullOrBlank()) {
                Glide.with(ivArtwork)
                    .load(entry.artworkUri)
                    .centerCrop()
                    .placeholder(R.drawable.bg_card_placeholder)
                    .into(ivArtwork)
            } else {
                ivArtwork.setImageResource(R.drawable.bg_card_placeholder)
            }

            cardRoot.setOnClickListener { onClick(entry) }
            cardRoot.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
                v.animate()
                    .translationZ(if (hasFocus) 8f else 0f)
                    .setDuration(120).start()
                btnRemove.visibility = if (hasFocus) View.VISIBLE else View.GONE
            }
            cardRoot.isFocusable = true

            btnRemove.setOnClickListener { onRemove(entry) }
        }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<ContinueWatchingEntry>() {
            override fun areItemsTheSame(a: ContinueWatchingEntry, b: ContinueWatchingEntry) =
                a.id == b.id
            override fun areContentsTheSame(a: ContinueWatchingEntry, b: ContinueWatchingEntry) =
                a == b
        }
    }
}
