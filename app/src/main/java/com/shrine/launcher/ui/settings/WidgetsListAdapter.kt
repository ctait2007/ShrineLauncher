package com.shrine.launcher.ui.settings

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.shrine.launcher.R
import com.shrine.launcher.data.model.PinnedWidget

class WidgetsListAdapter(
    private val onRemove: (PinnedWidget) -> Unit
) : RecyclerView.Adapter<WidgetsListAdapter.VH>() {

    private var widgets: List<PinnedWidget> = emptyList()

    fun setWidgets(list: List<PinnedWidget>) {
        widgets = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_settings_widget, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) =
        holder.bind(widgets[position])

    override fun getItemCount() = widgets.size

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        private val tvName: TextView    = view.findViewById(R.id.tvWidgetName)
        private val tvType: TextView    = view.findViewById(R.id.tvWidgetType)
        private val btnDel: ImageButton = view.findViewById(R.id.btnRemoveWidget)

        fun bind(widget: PinnedWidget) {
            tvName.text = widget.title
            tvType.text = widget.type.name.replace('_', ' ')
            btnDel.setOnClickListener { onRemove(widget) }

            itemView.isFocusable = true
            itemView.setOnFocusChangeListener { v, hasFocus -> applyGlow(v, hasFocus) }
            btnDel.setOnFocusChangeListener { v, hasFocus -> applyGlow(v, hasFocus) }
        }

        private fun applyGlow(v: android.view.View, hasFocus: Boolean) {
            val dp = v.resources.displayMetrics.density
            val bg = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                setColor(if (hasFocus) 0x11FFFFFF.toInt() else 0x00000000)
                setStroke(if (hasFocus) (2 * dp).toInt() else 0,
                    if (hasFocus) 0xFFFFFFFF.toInt() else 0x00000000)
                cornerRadius = 6 * dp
            }
            v.background = bg
        }
    }
}
