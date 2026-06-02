package com.shrine.launcher.ui.home

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import com.shrine.launcher.R
import com.shrine.launcher.data.model.CategoryType
import com.shrine.launcher.data.model.LauncherRow
import com.shrine.launcher.data.repository.PreferencesRepository
import com.shrine.launcher.ui.settings.AppPickerActivity

/**
 * Shows the category editor fields inside a panel-shaped dialog (same visual shell as the
 * settings panel) instead of a free-floating dialog box.
 */
class CategoryEditorPanelDialog(
    context: Context,
    private val row: LauncherRow,
    private val onSaved: () -> Unit
) : Dialog(context) {

    private val prefRepo by lazy { PreferencesRepository.getInstance(context) }
    private val sizeLabels = arrayOf<String?>(null, "S", "M", "L", "XL")
    private val sizeNames  = arrayOf("Global", "S — Small", "M — Medium", "L — Large", "XL — Extra Large")
    private var currentSizeLabel: String? = row.iconSizeLabelOverride

    private val isBuiltIn = row.categoryType in listOf(
        CategoryType.ALL_APPS, CategoryType.FAVORITES, CategoryType.INSTALL
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.dialog_settings_panel)
        configureWindow()
        buildEditor()
    }

    private fun configureWindow() {
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.END or Gravity.FILL_VERTICAL)
            val w = (context.resources.displayMetrics.widthPixels * 0.30f).toInt()
            setLayout(w, WindowManager.LayoutParams.MATCH_PARENT)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
    }

    private fun buildEditor() {
        // Header
        val tvDate = findViewById<TextView>(R.id.tvPanelDate)
        tvDate.visibility = View.GONE
        val ivLogo = findViewById<ImageView>(R.id.ivPanelLogo)
        ivLogo.visibility = View.GONE

        // Replace header content with a simple title
        val header = findViewById<LinearLayout>(R.id.panelHeader)
        val tv = TextView(context)
        tv.text = row.title
        tv.setTextColor(0xFFFFFFFF.toInt())
        tv.textSize = 16f
        tv.typeface = android.graphics.Typeface.DEFAULT_BOLD
        tv.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        header.removeAllViews()
        header.addView(tv)

        val body    = findViewById<LinearLayout>(R.id.panelBody)
        val helper  = SettingsPanelDialog(context)
        val inflater = LayoutInflater.from(context)

        // Title field
        helper.addSectionHeader(body, "TITLE")
        val etTitle = EditText(context)
        etTitle.setText(row.title)
        etTitle.setTextColor(0xFFFFFFFF.toInt())
        etTitle.setHintTextColor(0xFF666666.toInt())
        etTitle.hint = "Category name"
        etTitle.textSize = 14f
        etTitle.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(0x33FFFFFF)
            cornerRadius = 6 * context.resources.displayMetrics.density
        }
        val dp = context.resources.displayMetrics.density
        val pad = (10 * dp).toInt()
        etTitle.setPadding(pad, pad / 2, pad, pad / 2)
        val etLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        etLp.setMargins((16 * dp).toInt(), (4 * dp).toInt(), (16 * dp).toInt(), 0)
        etTitle.layoutParams = etLp
        body.addView(etTitle)

        // Visible toggle
        helper.addSectionHeader(body, "VISIBILITY")
        var isVisible = row.isVisible
        val visView = addToggleRow(body, helper, "Visible", isVisible) { isVisible = it }

        // Pinned toggle
        helper.addSectionHeader(body, "PINNED TO TOP")
        var isPinned = row.isPinnedToTop
        addToggleRow(body, helper, "Pinned", isPinned) { isPinned = it }

        // Size
        helper.addSectionHeader(body, "ICON SIZE")
        val sizeView = inflater.inflate(R.layout.item_panel_menu_entry, body, false)
        val tvSize   = sizeView.findViewById<TextView>(R.id.tvEntryLabel)
        sizeView.findViewById<ImageView>(R.id.ivEntryIcon).visibility = View.GONE
        sizeView.findViewById<ImageView>(R.id.ivEntryArrow).visibility = View.GONE
        tvSize.text = sizeNames[sizeLabels.indexOfFirst { it == currentSizeLabel }.coerceAtLeast(0)]
        helper.applyFocusBehavior(sizeView, tvSize, null)
        sizeView.setOnClickListener {
            val curr = sizeLabels.indexOfFirst { it == currentSizeLabel }.coerceAtLeast(0)
            AlertDialog.Builder(context)
                .setTitle("Icon Size")
                .setSingleChoiceItems(sizeNames, curr) { d, which ->
                    currentSizeLabel = sizeLabels[which]; tvSize.text = sizeNames[which]; d.dismiss()
                }
                .setNegativeButton("Cancel", null).show()
        }
        body.addView(sizeView)

        // Manage Apps
        if (!isBuiltIn || row.categoryType == CategoryType.FAVORITES) {
            if (row.categoryType !in listOf(CategoryType.ALL_APPS, CategoryType.INSTALL)) {
                helper.addSectionHeader(body, "APPS")
                helper.addMenuItem(body, "Manage Apps", R.drawable.ic_grid, showArrow = true) {
                    context.startActivity(Intent(context, AppPickerActivity::class.java).apply {
                        putExtra("row_id", row.id)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                }
            }
        }

        // Delete (custom rows only)
        if (!isBuiltIn) {
            helper.addSectionHeader(body, "DANGER ZONE")
            helper.addMenuItem(body, "Delete category", R.drawable.ic_add, showArrow = false) {
                AlertDialog.Builder(context)
                    .setTitle("Delete \"${row.title}\"?")
                    .setMessage("This cannot be undone.")
                    .setPositiveButton("Delete") { _, _ ->
                        prefRepo.removeRow(row.id); onSaved(); dismiss()
                    }
                    .setNegativeButton("Cancel", null).show()
            }.also { v ->
                v.findViewById<TextView>(R.id.tvEntryLabel)?.setTextColor(0xFFCF6679.toInt())
            }
        }

        // Save / Cancel at bottom
        helper.addSeparator(body)
        helper.addMenuItem(body, "Save", R.drawable.ic_arrow_right, showArrow = false) {
            val updated = row.copy(
                title                = etTitle.text.toString().ifBlank { row.title },
                isVisible            = isVisible,
                isPinnedToTop        = isPinned,
                iconSizeLabelOverride = currentSizeLabel
            )
            prefRepo.updateRow(updated)
            onSaved()
            dismiss()
        }.also { v ->
            v.findViewById<TextView>(R.id.tvEntryLabel)?.apply {
                setTextColor(0xFFE53935.toInt())
                textSize = 15f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
        }
        helper.addMenuItem(body, "Cancel", R.drawable.ic_add, showArrow = false) { dismiss() }
    }

    private fun addToggleRow(
        body: LinearLayout, helper: SettingsPanelDialog,
        label: String, initial: Boolean,
        onChange: (Boolean) -> Unit
    ): View {
        val inflater = LayoutInflater.from(context)
        val v = inflater.inflate(R.layout.item_panel_menu_entry, body, false)
        val tvLabel = v.findViewById<TextView>(R.id.tvEntryLabel)
        val ivIcon  = v.findViewById<ImageView>(R.id.ivEntryIcon)
        val ivArrow = v.findViewById<ImageView>(R.id.ivEntryArrow)
        tvLabel.text = label
        ivIcon.visibility = View.GONE
        ivArrow.visibility = View.GONE

        // Show current state as a coloured dot
        var state = initial
        val dot = TextView(context)
        dot.text = if (state) "ON" else "OFF"
        dot.setTextColor(if (state) 0xFFE53935.toInt() else 0xFF666666.toInt())
        dot.textSize = 12f
        (v as LinearLayout).addView(dot)

        helper.applyFocusBehavior(v, tvLabel, null)
        v.setOnClickListener {
            state = !state
            dot.text = if (state) "ON" else "OFF"
            dot.setTextColor(if (state) 0xFFE53935.toInt() else 0xFF666666.toInt())
            onChange(state)
        }
        body.addView(v)
        return v
    }

    override fun onBackPressed() { dismiss() }
}
