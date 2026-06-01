package com.shrine.launcher.ui.settings

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.shrine.launcher.R
import com.shrine.launcher.data.model.AppInfo
import com.shrine.launcher.data.repository.AppRepository
import com.shrine.launcher.databinding.ActivityAppPickerBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppPickerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppPickerBinding
    private var allApps: List<AppInfo> = emptyList()
    private lateinit var adapter: PickerAdapter

    companion object {
        const val RESULT_PACKAGE = "result_package"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = PickerAdapter { app ->
            val result = Intent().putExtra(RESULT_PACKAGE, app.packageName)
            setResult(Activity.RESULT_OK, result)
            finish()
        }

        binding.rvPickerApps.apply {
            layoutManager = LinearLayoutManager(this@AppPickerActivity)
            adapter = this@AppPickerActivity.adapter
        }

        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterApps(s.toString())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.btnBack.setOnClickListener { finish() }

        loadApps()
    }

    private fun loadApps() {
        CoroutineScope(Dispatchers.Main).launch {
            allApps = withContext(Dispatchers.IO) {
                AppRepository.getInstance(this@AppPickerActivity).getAllApps()
            }
            adapter.setApps(allApps)
        }
    }

    private fun filterApps(query: String) {
        val filtered = if (query.isBlank()) allApps
        else allApps.filter { it.label.contains(query, ignoreCase = true) }
        adapter.setApps(filtered)
    }
}

class PickerAdapter(
    private val onPick: (AppInfo) -> Unit
) : RecyclerView.Adapter<PickerAdapter.VH>() {

    private var apps: List<AppInfo> = emptyList()

    fun setApps(list: List<AppInfo>) {
        apps = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_picker_app, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(apps[position])
    override fun getItemCount() = apps.size

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        private val ivIcon: ImageView = view.findViewById(R.id.ivPickerIcon)
        private val tvName: TextView  = view.findViewById(R.id.tvPickerName)

        fun bind(app: AppInfo) {
            ivIcon.setImageDrawable(app.icon)
            tvName.text = app.label
            itemView.setOnClickListener { onPick(app) }
        }
    }
}
