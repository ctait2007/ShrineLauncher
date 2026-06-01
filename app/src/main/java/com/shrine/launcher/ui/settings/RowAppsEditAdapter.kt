package com.shrine.launcher.ui.settings

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.shrine.launcher.R
import com.shrine.launcher.data.repository.AppRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RowAppsEditAdapter(
    private val packages: List<String>,
    private val appRepo: AppRepository,
    private val onRemove: (String) -> Unit
) : RecyclerView.Adapter<RowAppsEditAdapter.VH>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_row_app_edit, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) =
        holder.bind(packages[position])

    override fun getItemCount() = packages.size

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        private val ivIcon:  ImageView  = view.findViewById(R.id.ivEditAppIcon)
        private val tvName:  TextView   = view.findViewById(R.id.tvEditAppName)
        private val btnDel:  ImageButton = view.findViewById(R.id.btnRemoveAppFromRow)

        fun bind(pkg: String) {
            CoroutineScope(Dispatchers.Main).launch {
                val info = withContext(Dispatchers.IO) { appRepo.getAppInfo(pkg) }
                tvName.text = info?.label ?: pkg
                ivIcon.setImageDrawable(info?.icon)
            }
            btnDel.setOnClickListener { onRemove(pkg) }
        }
    }
}
