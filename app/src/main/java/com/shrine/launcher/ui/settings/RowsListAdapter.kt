package com.shrine.launcher.ui.settings

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.shrine.launcher.R
import com.shrine.launcher.data.model.CardDisplayMode
import com.shrine.launcher.data.model.LauncherRow
import com.shrine.launcher.data.model.RowKind

class RowsListAdapter(
    private val onEdit: (LauncherRow) -> Unit,
    private val onDelete: (LauncherRow) -> Unit,
    private val onVisibilityToggle: (LauncherRow) -> Unit,
    private val onDisplayModeToggle: (LauncherRow) -> Unit
) : RecyclerView.Adapter<RowsListAdapter.VH>() {

    var currentRows: List<LauncherRow> = emptyList()
        private set

    fun setRows(rows: List<LauncherRow>) { currentRows = rows; notifyDataSetChanged() }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_settings_row, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) =
        holder.bind(currentRows[position])

    override fun getItemCount() = currentRows.size

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        private val tvName:   TextView    = view.findViewById(R.id.tvSettingsRowName)
        private val tvType:   TextView    = view.findViewById(R.id.tvSettingsRowType)
        private val tvMode:   TextView    = view.findViewById(R.id.tvSettingsRowMode)
        private val btnMode:  ImageButton = view.findViewById(R.id.btnToggleDisplayMode)
        private val btnVis:   ImageButton = view.findViewById(R.id.btnToggleVisibility)
        private val btnEdit:  ImageButton = view.findViewById(R.id.btnEditRow)
        private val btnDel:   ImageButton = view.findViewById(R.id.btnDeleteRow)

        fun bind(row: LauncherRow) {
            tvName.text = row.title
            tvType.text = when (row.kind) {
                RowKind.CHANNEL  -> "CHANNEL • ${row.channelType?.name?.replace('_', ' ')}"
                RowKind.CATEGORY -> "APPS • ${row.categoryType?.name?.replace('_', ' ')}"
            }
            val pinLabel = if (row.isPinnedToTop) " 📌" else ""
            tvMode.text = (if (row.cardDisplayMode == CardDisplayMode.BANNER) "● BANNER" else "● ICON") + pinLabel

            btnVis.setImageResource(
                if (row.isVisible) R.drawable.ic_visibility_on else R.drawable.ic_visibility_off
            )
            btnMode.setImageResource(
                if (row.cardDisplayMode == CardDisplayMode.BANNER) R.drawable.ic_banner
                else R.drawable.ic_grid
            )

            btnEdit.setOnClickListener  { onEdit(row) }
            btnDel.setOnClickListener   { onDelete(row) }
            btnVis.setOnClickListener   { onVisibilityToggle(row) }
            btnMode.setOnClickListener  { onDisplayModeToggle(row) }

            listOf(btnMode, btnVis, btnEdit, btnDel).forEach { btn ->
                btn.setOnFocusChangeListener { v, hasFocus ->
                    val dp = v.resources.displayMetrics.density
                    val bg = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                        setColor(0x00000000)
                        setStroke(if (hasFocus) (2 * dp).toInt() else 0,
                            if (hasFocus) 0xFFFFFFFF.toInt() else 0)
                        cornerRadius = 6 * dp
                    }
                    v.background = bg
                }
            }
        }
    }
}
