package com.shrine.launcher.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.shrine.launcher.R
import com.shrine.launcher.databinding.ActivityShrineSettingsBinding

class ShrineSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityShrineSettingsBinding

    private data class SubSection(val label: String, val iconRes: Int, val action: () -> Unit)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityShrineSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val wallpaperUri = intent.getStringExtra("wallpaper_uri")
        wallpaperUri?.let {
            try { Glide.with(this).load(Uri.parse(it)).centerCrop().into(binding.ivShrineSettingsBg) }
            catch (e: Exception) { }
        }

        val sections = listOf(
            SubSection("General", R.drawable.ic_settings) {
                startActivity(Intent(this, GeneralSettingsActivity::class.java)
                    .apply { wallpaperUri?.let { putExtra("wallpaper_uri", it) } })
            },
            SubSection("Appearance", R.drawable.ic_grid) {
                startActivity(Intent(this, AppearanceSettingsActivity::class.java)
                    .apply { wallpaperUri?.let { putExtra("wallpaper_uri", it) } })
            },
            SubSection("Manage Settings", R.drawable.ic_install) {
                startActivity(Intent(this, ManageSettingsActivity::class.java)
                    .apply { wallpaperUri?.let { putExtra("wallpaper_uri", it) } })
            }
        )

        binding.rvShrineSettings.layoutManager = LinearLayoutManager(this)
        binding.rvShrineSettings.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = sections.size
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val v = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_settings_subsection, parent, false)
                return object : RecyclerView.ViewHolder(v) {}
            }
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val s = sections[position]
                val v = holder.itemView
                val tvLabel = v.findViewById<TextView>(R.id.tvSubLabel)
                val ivIcon  = v.findViewById<ImageView>(R.id.ivSubIcon)
                tvLabel.text = s.label
                try { ivIcon.setImageResource(s.iconRes) } catch (e: Exception) { }
                applyFocus(v, tvLabel)
                v.setOnClickListener { s.action() }
            }
        }
    }

    private fun applyFocus(v: View, label: TextView) {
        val dp = resources.displayMetrics.density
        v.setOnFocusChangeListener { view, hasFocus ->
            val bg = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                setColor(if (hasFocus) 0x1AFFFFFF.toInt() else 0x00000000)
                setStroke(if (hasFocus) (2 * dp).toInt() else 0,
                    if (hasFocus) 0xFFFFFFFF.toInt() else 0x00000000)
                cornerRadius = 8 * dp
            }
            view.background = bg
            label.setTextColor(if (hasFocus) 0xFFFFFFFF.toInt() else 0xFFB0B0B0.toInt())
        }
    }
}
