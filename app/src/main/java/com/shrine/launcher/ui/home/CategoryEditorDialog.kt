package com.shrine.launcher.ui.home

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
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

class CategoryEditorDialog(
    context: Context,
    private val row: LauncherRow,
    private val onSaved: () -> Unit
) : Dialog(context) {

    private val prefRepo by lazy { PreferencesRepository.getInstance(context) }
    private val sizeLabels = arrayOf<String?>(null, "S", "M", "L", "XL")
    private val sizeDisplayNames = arrayOf("Global", "S — Small", "M — Medium", "L — Large", "XL — Extra Large")
    private var currentSizeLabel: String? = row.iconSizeLabelOverride

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.dialog_category_editor)
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT)
        }

        val isBuiltIn = row.categoryType in listOf(
            CategoryType.ALL_APPS, CategoryType.FAVORITES, CategoryType.INSTALL
        )

        val tvTitle   = findViewById<TextView>(R.id.tvEditorTitle)
        val etTitle   = findViewById<EditText>(R.id.etRowTitle)
        val switchVis = findViewById<Switch>(R.id.switchVisible)
        val switchPin = findViewById<Switch>(R.id.switchPinned)
        val tvSize    = findViewById<TextView>(R.id.tvSizeValue)
        val rowManage = findViewById<LinearLayout>(R.id.rowManageApps)
        val rowDelete = findViewById<LinearLayout>(R.id.rowDelete)
        val rowSize   = findViewById<LinearLayout>(R.id.rowSize)

        tvTitle.text = row.title
        etTitle.setText(row.title)
        switchVis.isChecked = row.isVisible
        switchPin.isChecked = row.isPinnedToTop
        tvSize.text = sizeDisplayNames[sizeLabels.indexOfFirst { it == currentSizeLabel }.coerceAtLeast(0)]

        // Built-in rows: no delete; ALL_APPS has no manage apps (it shows everything)
        rowDelete.visibility = if (isBuiltIn) View.GONE else View.VISIBLE
        rowManage.visibility = when (row.categoryType) {
            CategoryType.ALL_APPS, CategoryType.INSTALL -> View.GONE
            else -> View.VISIBLE
        }

        setupFocus(findViewById<LinearLayout>(R.id.rowVisible))
        setupFocus(findViewById<LinearLayout>(R.id.rowPinned))
        setupFocus(rowSize)
        setupFocus(rowManage)
        setupFocus(rowDelete)
        setupFocus(findViewById<TextView>(R.id.btnEditorCancel))
        setupFocus(findViewById<TextView>(R.id.btnEditorSave))

        findViewById<LinearLayout>(R.id.rowVisible).setOnClickListener {
            switchVis.isChecked = !switchVis.isChecked
        }
        findViewById<LinearLayout>(R.id.rowPinned).setOnClickListener {
            switchPin.isChecked = !switchPin.isChecked
        }
        rowSize.setOnClickListener { showSizePicker(tvSize) }
        rowManage.setOnClickListener {
            context.startActivity(Intent(context, AppPickerActivity::class.java).apply {
                putExtra("row_id", row.id)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
        rowDelete.setOnClickListener {
            AlertDialog.Builder(context)
                .setTitle("Delete category?")
                .setMessage("\"${row.title}\" will be removed.")
                .setPositiveButton("Delete") { _, _ ->
                    prefRepo.removeRow(row.id)
                    onSaved()
                    dismiss()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        findViewById<TextView>(R.id.btnEditorCancel).setOnClickListener { dismiss() }
        findViewById<TextView>(R.id.btnEditorSave).setOnClickListener {
            val updated = row.copy(
                title = etTitle.text.toString().ifBlank { row.title },
                isVisible = switchVis.isChecked,
                isPinnedToTop = switchPin.isChecked,
                iconSizeLabelOverride = currentSizeLabel
            )
            prefRepo.updateRow(updated)
            onSaved()
            dismiss()
        }
    }

    private fun showSizePicker(tvSize: TextView) {
        val currentIdx = sizeLabels.indexOfFirst { it == currentSizeLabel }.coerceAtLeast(0)
        AlertDialog.Builder(context)
            .setTitle("Icon Size")
            .setSingleChoiceItems(sizeDisplayNames, currentIdx) { dialog, which ->
                currentSizeLabel = sizeLabels[which]
                tvSize.text = sizeDisplayNames[which]
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setupFocus(v: View) {
        val dp = v.resources.displayMetrics.density
        v.setOnFocusChangeListener { view, hasFocus ->
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(if (hasFocus) 0x1AFFFFFF.toInt() else 0x00000000)
                setStroke(
                    if (hasFocus) (2 * dp).toInt() else 0,
                    if (hasFocus) 0xFFFFFFFF.toInt() else 0x00000000
                )
                cornerRadius = 8 * dp
            }
            view.background = bg
        }
    }
}
