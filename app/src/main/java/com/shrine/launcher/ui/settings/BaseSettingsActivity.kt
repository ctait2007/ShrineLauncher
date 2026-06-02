package com.shrine.launcher.ui.settings

import android.net.Uri
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.shrine.launcher.R
import com.shrine.launcher.data.model.LauncherPrefs
import com.shrine.launcher.data.repository.PreferencesRepository
import com.shrine.launcher.databinding.ActivitySettingsSubBinding

abstract class BaseSettingsActivity : AppCompatActivity() {

    protected lateinit var binding: ActivitySettingsSubBinding
    protected val prefRepo by lazy { PreferencesRepository.getInstance(this) }

    protected fun setupBase(title: String, wallpaperUri: String?) {
        binding = ActivitySettingsSubBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.tvSubScreenTitle.text = "SETTINGS"
        binding.tvSubScreenName.text  = title
        wallpaperUri?.let {
            try {
                Glide.with(this).load(Uri.parse(it)).centerCrop().into(binding.ivSubBg)
            } catch (e: Exception) { }
        }
    }

    protected fun addToggle(
        label: String,
        subtitle: String? = null,
        isChecked: Boolean,
        onChange: (Boolean) -> Unit
    ): View {
        val v = LayoutInflater.from(this).inflate(R.layout.item_settings_toggle, binding.llSubContent, false)
        val tvLabel    = v.findViewById<TextView>(R.id.tvToggleLabel)
        val tvSub      = v.findViewById<TextView>(R.id.tvToggleSubtitle)
        val sw         = v.findViewById<Switch>(R.id.switchToggle)
        tvLabel.text = label
        if (subtitle != null) { tvSub.text = subtitle; tvSub.visibility = View.VISIBLE }
        sw.isChecked = isChecked
        applyFocus(v)
        v.setOnClickListener { sw.isChecked = !sw.isChecked; onChange(sw.isChecked) }
        binding.llSubContent.addView(v)
        addDivider()
        return v
    }

    protected fun addButton(
        label: String,
        subtitle: String? = null,
        textColor: Int = 0xFFB0B0B0.toInt(),
        onClick: () -> Unit
    ): View {
        val v = LayoutInflater.from(this).inflate(R.layout.item_settings_button, binding.llSubContent, false)
        val tvLabel = v.findViewById<TextView>(R.id.tvButtonLabel)
        val tvSub   = v.findViewById<TextView>(R.id.tvButtonSubtitle)
        tvLabel.text = label
        tvLabel.setTextColor(textColor)
        if (subtitle != null) { tvSub.text = subtitle; tvSub.visibility = View.VISIBLE }
        applyFocus(v)
        v.setOnClickListener { onClick() }
        binding.llSubContent.addView(v)
        addDivider()
        return v
    }

    protected fun addSlider(
        label: String,
        value: Int,
        min: Int,
        max: Int,
        step: Int = 1,
        displayFn: (Int) -> String = { it.toString() },
        onChange: (Int) -> Unit
    ): View {
        val v = LayoutInflater.from(this).inflate(R.layout.item_settings_slider, binding.llSubContent, false)
        val tvLabel = v.findViewById<TextView>(R.id.tvSliderLabel)
        val tvValue = v.findViewById<TextView>(R.id.tvSliderValue)
        val seek    = v.findViewById<SeekBar>(R.id.seekSlider)
        tvLabel.text = label
        val steps = (max - min) / step
        seek.max = steps
        var current = value.coerceIn(min, max)
        seek.progress = (current - min) / step
        tvValue.text = displayFn(current)
        // D-pad left/right adjusts the slider
        v.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (current - step >= min) {
                        current -= step
                        seek.progress = (current - min) / step
                        tvValue.text = displayFn(current)
                        onChange(current)
                    }
                    true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (current + step <= max) {
                        current += step
                        seek.progress = (current - min) / step
                        tvValue.text = displayFn(current)
                        onChange(current)
                    }
                    true
                }
                else -> false
            }
        }
        applyFocus(v)
        binding.llSubContent.addView(v)
        addDivider()
        return v
    }

    protected fun addSectionHeader(text: String) {
        val tv = TextView(this)
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, 24, 0, 4)
        tv.layoutParams = lp
        tv.text = text
        tv.setTextColor(0xFFE53935.toInt())
        tv.textSize = 11f
        tv.letterSpacing = 0.1f
        binding.llSubContent.addView(tv)
    }

    private fun addDivider() {
        val v = View(this)
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            (0.5f * resources.displayMetrics.density).toInt())
        v.layoutParams = lp
        v.setBackgroundColor(0x22FFFFFF)
        binding.llSubContent.addView(v)
    }

    protected fun applyFocus(v: View) {
        val dp = resources.displayMetrics.density
        v.setOnFocusChangeListener { view, hasFocus ->
            val bg = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                setColor(if (hasFocus) 0x1AFFFFFF.toInt() else 0x00000000)
                setStroke(if (hasFocus) (2 * dp).toInt() else 0,
                    if (hasFocus) 0xFFE53935.toInt() else 0x00000000)
                cornerRadius = 8 * dp
            }
            view.background = bg
        }
    }

    protected fun saveAndRefresh(transform: (LauncherPrefs) -> LauncherPrefs) {
        val updated = transform(prefRepo.loadPrefs())
        prefRepo.savePrefs(updated)
    }
}
