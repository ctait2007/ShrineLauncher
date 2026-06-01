package com.shrine.launcher.ui.home

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.shrine.launcher.R
import com.shrine.launcher.data.model.AppInfo
import com.shrine.launcher.data.model.CardDisplayMode

class AppsAdapter(
    private val displayMode: CardDisplayMode = CardDisplayMode.ICON,
    private val cornerRadiusPercent: Int = 50,
    private val iconSizeDp: Int = 88,
    private val onAppClick: (AppInfo) -> Unit,
    private val onAppLongClick: (AppInfo) -> Unit,
    private val onFocused: () -> Unit,
    private val onLeftFromFirst: (() -> Unit)? = null,
) : ListAdapter<AppInfo, AppsAdapter.AppViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val layout = if (displayMode == CardDisplayMode.BANNER)
            R.layout.item_app_banner else R.layout.item_app_card
        val v = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        return AppViewHolder(v)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) =
        holder.bind(getItem(position))

    inner class AppViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val focusBorderFrame: FrameLayoutCompat = view.findViewById(R.id.focusBorderFrame)
        private val cardRoot:   View      = view.findViewById(R.id.cardRoot)
        private val ivIcon:     ImageView = view.findViewById(R.id.ivAppIcon)
        private val tvName:     TextView  = view.findViewById(R.id.tvAppName)
        private val tvFallback: TextView? = view.findViewById(R.id.tvFallbackLabel)

        fun bind(app: AppInfo) {
            val density = cardRoot.resources.displayMetrics.density
            val sizePx  = (iconSizeDp * density).toInt()
            val params  = cardRoot.layoutParams
            if (displayMode == CardDisplayMode.ICON) {
                params.width  = sizePx
                params.height = sizePx
            } else {
                params.height = sizePx
                params.width  = (sizePx * 16f / 9f).toInt()
            }
            cardRoot.layoutParams = params
            cardRoot.requestLayout()
            val borderParams = focusBorderFrame.layoutParams
            val pad = (6 * density).toInt()
            if (displayMode == CardDisplayMode.ICON) {
                borderParams.width  = sizePx + pad
                borderParams.height = sizePx + pad
            } else {
                borderParams.height = sizePx + pad
                borderParams.width  = (sizePx * 16f / 9f).toInt() + pad
            }
            focusBorderFrame.layoutParams = borderParams
            focusBorderFrame.requestLayout()

            applyCornerRadius(cardRoot, cornerRadiusPercent)

            tvName.visibility = View.GONE

            if (displayMode == CardDisplayMode.BANNER) bindBanner(app)
            else bindIcon(app)

            cardRoot.setOnClickListener { onAppClick(app) }
            cardRoot.setOnLongClickListener { onAppLongClick(app); true }
            cardRoot.isFocusable = true
            cardRoot.isFocusableInTouchMode = false

            cardRoot.setOnKeyListener { _, keyCode, event ->
                if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT
                    && event.action == android.view.KeyEvent.ACTION_DOWN
                    && adapterPosition == 0) {
                    onLeftFromFirst?.invoke()
                    true
                } else false
            }

            tvName.text = app.label
            cardRoot.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
                if (hasFocus && v.hasWindowFocus()) onFocused()
                applyFocusBorder(focusBorderFrame, hasFocus, cornerRadiusPercent)
                tvName.visibility = if (hasFocus) View.VISIBLE else View.GONE
                cardRoot.animate()
                    .translationZ(if (hasFocus) 8f else 0f)
                    .setDuration(120).start()
            }
        }

        private fun applyCornerRadius(v: View, radiusPct: Int) {
            val density = v.resources.displayMetrics.density
            val h       = v.layoutParams?.height?.takeIf { it > 0 }
                ?: (iconSizeDp * density).toInt()
            val radius  = h * (radiusPct / 100f) * 0.5f
            val bg = GradientDrawable().apply {
                shape        = GradientDrawable.RECTANGLE
                setColor(0xFF1E1E1E.toInt())
                cornerRadius = radius
            }
            v.background    = bg
            v.clipToOutline = true
            v.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        }

        private fun applyFocusBorder(v: View, focused: Boolean, radiusPct: Int) {
            val density  = v.resources.displayMetrics.density
            val innerH   = cardRoot.layoutParams?.height?.takeIf { it > 0 }
                ?: (iconSizeDp * density).toInt()
            val outerH   = innerH + (6 * density).toInt()
            val radius   = outerH * (radiusPct / 100f) * 0.5f + (3 * density)
            val strokePx = if (focused) (3 * density).toInt() else 0

            val bg = GradientDrawable().apply {
                shape        = GradientDrawable.RECTANGLE
                setColor(0x00000000)
                cornerRadius = radius
                setStroke(strokePx,
                    if (focused) 0xFFFFFFFF.toInt() else 0x00000000)
            }
            v.background = bg
        }

        private fun bindIcon(app: AppInfo) {
            ivIcon.setImageDrawable(app.icon)
            ivIcon.scaleType = ImageView.ScaleType.FIT_XY
        }

        private fun bindBanner(app: AppInfo) {
            val banner = try {
                val pm = itemView.context.packageManager
                pm.getApplicationBanner(app.packageName)
                    ?: pm.getActivityBanner(pm.getLaunchIntentForPackage(app.packageName)!!)
            } catch (e: Exception) { null }

            if (banner != null) {
                ivIcon.setImageDrawable(banner)
                ivIcon.scaleType = ImageView.ScaleType.FIT_XY
                tvFallback?.visibility = View.GONE
            } else {
                ivIcon.setImageDrawable(app.icon)
                ivIcon.scaleType = ImageView.ScaleType.CENTER_INSIDE
            }
        }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<AppInfo>() {
            override fun areItemsTheSame(a: AppInfo, b: AppInfo) =
                a.packageName == b.packageName
            override fun areContentsTheSame(a: AppInfo, b: AppInfo) =
                a.packageName == b.packageName && a.label == b.label
        }
    }
}

// Type alias so we can reference FrameLayout without full import collision
private typealias FrameLayoutCompat = android.widget.FrameLayout
