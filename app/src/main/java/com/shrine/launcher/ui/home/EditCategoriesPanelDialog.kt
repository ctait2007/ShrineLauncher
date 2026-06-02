package com.shrine.launcher.ui.home

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.shrine.launcher.R
import com.shrine.launcher.data.model.CategoryType
import com.shrine.launcher.data.model.LauncherRow
import com.shrine.launcher.data.model.RowKind
import com.shrine.launcher.data.repository.PreferencesRepository

class EditCategoriesPanelDialog(
    context: Context,
    private val wallpaperUri: String? = null
) : Dialog(context) {

    private val prefRepo by lazy { PreferencesRepository.getInstance(context) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.dialog_settings_panel)
        configureWindow()
        setupHeader()
        buildList()
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

    private fun setupHeader() {
        val tvDate = findViewById<TextView>(R.id.tvPanelDate)
        tvDate.text = "Edit Categories"
        findViewById<ImageView>(R.id.ivPanelLogo).visibility = View.GONE
    }

    private fun buildList() {
        val body = findViewById<LinearLayout>(R.id.panelBody)
        body.removeAllViews()
        val inflater = LayoutInflater.from(context)
        val helper = SettingsPanelDialog(context)

        helper.addMenuItem(body, "+ New Category", R.drawable.ic_add, showArrow = false) {
            createNewCategory()
        }

        helper.addSectionHeader(body, "CATEGORIES")

        val rows = prefRepo.loadRows().filter { it.kind == RowKind.CATEGORY }
        rows.forEach { row ->
            val v = inflater.inflate(R.layout.item_panel_menu_entry, body, false)
            val tvLabel = v.findViewById<TextView>(R.id.tvEntryLabel)
            val ivIcon  = v.findViewById<ImageView>(R.id.ivEntryIcon)
            val ivArrow = v.findViewById<ImageView>(R.id.ivEntryArrow)
            tvLabel.text = row.title
            ivIcon.visibility = View.GONE
            ivArrow.visibility = View.GONE
            if (!row.isVisible) tvLabel.alpha = 0.5f
            helper.applyFocusBehavior(v, tvLabel, null)
            v.setOnClickListener {
                CategoryEditorDialog(context, row) { buildList() }.show()
            }
            body.addView(v)
        }
    }

    private fun createNewCategory() {
        val newRow = LauncherRow(
            id = "row_custom_${System.currentTimeMillis()}",
            title = "New Category",
            kind = RowKind.CATEGORY,
            categoryType = CategoryType.CUSTOM
        )
        prefRepo.addRow(newRow)
        buildList()
        CategoryEditorDialog(context, newRow) { buildList() }.show()
    }

    override fun onBackPressed() { dismiss() }
}
