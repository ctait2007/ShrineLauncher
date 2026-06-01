package com.shrine.launcher.ui.home

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import com.bumptech.glide.Glide
import com.shrine.launcher.R
import com.shrine.launcher.data.model.TvContent

class ContentContextMenuDialog(
    context: Context,
    private val content: TvContent,
    private val onPlay: () -> Unit,
    private val onDismiss: () -> Unit
) : Dialog(context) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.dialog_content_context_menu)
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.CENTER)
            setLayout(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT)
        }

        val ivArt = findViewById<ImageView>(R.id.ivContentArt)
        if (!content.artworkUri.isNullOrBlank()) {
            Glide.with(ivArt).load(content.artworkUri).centerCrop()
                .placeholder(R.drawable.bg_card_placeholder).into(ivArt)
        } else {
            ivArt.setImageResource(R.drawable.bg_card_placeholder)
        }

        findViewById<TextView>(R.id.tvContentTitle).text    = content.title
        findViewById<TextView>(R.id.tvContentSubtitle).text = content.subtitle

        val btnPlay    = findViewById<TextView>(R.id.btnPlay)
        val btnDismiss = findViewById<TextView>(R.id.btnRemoveContent)
        val btnCancel  = findViewById<TextView>(R.id.btnContentCancel)

        btnDismiss.text = "Dismiss from row"

        listOf(btnPlay, btnDismiss, btnCancel).forEach { btn ->
            btn.isFocusable = true
            btn.setOnFocusChangeListener { v, hasFocus -> applyFocus(v as TextView, hasFocus) }
        }

        btnPlay.setOnClickListener    { onPlay(); dismiss() }
        btnDismiss.setOnClickListener { onDismiss(); dismiss() }
        btnCancel.setOnClickListener  { dismiss() }
    }

    private fun applyFocus(v: TextView, hasFocus: Boolean) {
        val dp = v.resources.displayMetrics.density
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(if (hasFocus) 0x22FFFFFF.toInt() else 0x00000000)
            setStroke(if (hasFocus) (2 * dp).toInt() else 0,
                if (hasFocus) 0xFFFFFFFF.toInt() else 0)
            cornerRadius = 8 * dp
        }
        v.background = bg
        v.setTextColor(if (hasFocus) 0xFFFFFFFF.toInt() else 0xFFB0B0B0.toInt())
    }
}
