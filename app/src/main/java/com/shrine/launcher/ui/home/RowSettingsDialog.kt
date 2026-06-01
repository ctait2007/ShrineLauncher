package com.shrine.launcher.ui.home

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.shrine.launcher.R
import com.shrine.launcher.data.model.AppInfo
import com.shrine.launcher.data.model.CategoryType
import com.shrine.launcher.data.model.LauncherRow
import com.shrine.launcher.data.model.RowKind
import com.shrine.launcher.data.repository.PreferencesRepository

class RowSettingsDialog(
    context: Context,
    private val row: LauncherRow,
    private val allApps: List<AppInfo>,
    private val onChanged: () -> Unit
) : Dialog(context) {

    private val repo = PreferencesRepository.getInstance(context)
    private lateinit var rv: RecyclerView
    private lateinit var btnSave: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.dialog_row_settings)
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(720, WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
        }

        val tvTitle    = findViewById<TextView>(R.id.tvRowSettingsTitle)
        val etTitle    = findViewById<EditText>(R.id.etRowSettingsTitle)
        rv             = findViewById(R.id.rvRowSettingsApps)
        val tvAppsHint = findViewById<TextView>(R.id.tvRowAppsHint)
        btnSave        = findViewById(R.id.btnRowSettingsSave)
        val btnClose   = findViewById<TextView>(R.id.btnRowSettingsClose)

        tvTitle.text = row.title
        etTitle.setText(row.title)

        if (row.kind == RowKind.CATEGORY) {
            tvAppsHint.visibility = View.VISIBLE
            tvAppsHint.text = "Select to include  •  ▲▼ to reorder"
            rv.visibility = View.VISIBLE

            val appMap = allApps.associateBy { it.packageName }
            val explicitlySelected: Set<String> = when {
                row.apps.isNotEmpty() -> row.apps.toSet()
                else -> allApps.map { it.packageName }.toSet()
            }
            val selectedInOrder = if (row.apps.isNotEmpty())
                row.apps.mapNotNull { appMap[it] }
            else
                allApps.filter { it.packageName in explicitlySelected }

            val unselectedApps = allApps.filter { it.packageName !in explicitlySelected }
            val workingList    = (selectedInOrder + unselectedApps).toMutableList()
            val selectedPkgs   = explicitlySelected.toMutableSet()

            val adapter = AppToggleAdapter(
                apps         = workingList,
                selectedPkgs = selectedPkgs,
                rv           = rv,
                onToggled    = { pos -> focusAfterToggle(pos, workingList.size) },
                onSaveRequest = { saveAndDismiss(etTitle, workingList, selectedPkgs) }
            )
            rv.layoutManager = LinearLayoutManager(context)
            rv.adapter = adapter
            rv.isFocusable = false
            rv.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS

            btnSave.setOnClickListener { saveAndDismiss(etTitle, workingList, selectedPkgs) }
        } else {
            tvAppsHint.visibility = View.GONE
            rv.visibility = View.GONE
            btnSave.setOnClickListener {
                repo.updateRow(row.copy(title = etTitle.text.toString().ifBlank { row.title }))
                onChanged(); dismiss()
            }
        }

        btnClose.setOnClickListener { dismiss() }
    }

    private fun saveAndDismiss(
        etTitle: EditText,
        workingList: MutableList<AppInfo>,
        selectedPkgs: MutableSet<String>
    ) {
        val finalApps = workingList
            .filter { it.packageName in selectedPkgs }
            .map { it.packageName }
            .toMutableList()
        repo.updateRow(row.copy(
            title        = etTitle.text.toString().ifBlank { row.title },
            apps         = finalApps,
            categoryType = CategoryType.CUSTOM
        ))
        onChanged(); dismiss()
    }

    private fun focusAfterToggle(pos: Int, total: Int) {
        val lm = rv.layoutManager as? LinearLayoutManager ?: return
        val nextPos = if (pos < total - 1) pos + 1 else -1
        if (nextPos >= 0) {
            rv.post {
                lm.scrollToPosition(nextPos)
                rv.post {
                    rv.findViewHolderForAdapterPosition(nextPos)
                        ?.itemView?.findViewById<View>(R.id.tvReorderName)?.requestFocus()
                }
            }
        } else {
            btnSave.requestFocus()
        }
    }
}

class AppToggleAdapter(
    private val apps: MutableList<AppInfo>,
    private val selectedPkgs: MutableSet<String>,
    private val rv: RecyclerView,
    private val onToggled: (pos: Int) -> Unit,
    private val onSaveRequest: () -> Unit
) : RecyclerView.Adapter<AppToggleAdapter.VH>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_row_app_reorder, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) =
        holder.bind(apps[position], position)

    override fun getItemCount() = apps.size

    fun move(from: Int, to: Int, focusUp: Boolean) {
        if (to < 0 || to >= apps.size) return
        apps.add(to, apps.removeAt(from))
        notifyItemMoved(from, to)
        notifyItemChanged(to)
        val neighbour = if (focusUp) to - 1 else to + 1
        if (neighbour in 0 until apps.size) notifyItemChanged(neighbour)

        rv.post {
            (rv.layoutManager as? LinearLayoutManager)?.scrollToPosition(to)
            rv.post {
                val vh = rv.findViewHolderForAdapterPosition(to) ?: return@post
                val btnId = if (focusUp) R.id.btnMoveUp else R.id.btnMoveDown
                vh.itemView.findViewById<View>(btnId)?.requestFocus()
            }
        }
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        private val ivIcon:  ImageView   = view.findViewById(R.id.ivReorderIcon)
        private val tvName:  TextView    = view.findViewById(R.id.tvReorderName)
        private val cb:      CheckBox    = view.findViewById(R.id.cbReorderVisible)
        private val btnUp:   ImageButton = view.findViewById(R.id.btnMoveUp)
        private val btnDown: ImageButton = view.findViewById(R.id.btnMoveDown)

        fun bind(app: AppInfo, pos: Int) {
            ivIcon.setImageDrawable(app.icon)
            tvName.text = app.label

            val selected = app.packageName in selectedPkgs
            cb.setOnCheckedChangeListener(null)
            cb.isChecked = selected
            tvName.alpha = if (selected) 1f else 0.4f
            ivIcon.alpha = if (selected) 1f else 0.4f

            val showMove = selected
            btnUp.visibility   = if (showMove) View.VISIBLE else View.INVISIBLE
            btnDown.visibility = if (showMove) View.VISIBLE else View.INVISIBLE

            val canUp   = showMove && pos > 0 && apps[pos - 1].packageName in selectedPkgs
            val canDown = showMove && pos < apps.size - 1 && apps[pos + 1].packageName in selectedPkgs
            btnUp.isEnabled   = canUp
            btnDown.isEnabled = canDown
            btnUp.alpha   = if (canUp) 1f else 0.25f
            btnDown.alpha = if (canDown) 1f else 0.25f
            btnUp.isFocusable   = showMove && canUp
            btnDown.isFocusable = showMove && canDown

            cb.isFocusable = false

            tvName.setOnFocusChangeListener { v, hasFocus -> applyGlow(v, hasFocus) }
            btnUp.setOnFocusChangeListener   { v, hasFocus -> applyGlow(v, hasFocus) }
            btnDown.setOnFocusChangeListener { v, hasFocus -> applyGlow(v, hasFocus) }

            tvName.setOnClickListener { doToggle(pos) }
            tvName.setOnKeyListener { _, keyCode, event ->
                if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
                    && event.action == KeyEvent.ACTION_DOWN) {
                    doToggle(pos); true
                } else false
            }

            cb.setOnCheckedChangeListener { _, checked ->
                if (checked) selectedPkgs.add(app.packageName)
                else selectedPkgs.remove(app.packageName)
                notifyItemChanged(pos)
                val prev = pos - 1; val next = pos + 1
                if (prev >= 0) notifyItemChanged(prev)
                if (next < apps.size) notifyItemChanged(next)
                onToggled(pos)
            }

            btnUp.setOnClickListener {
                val cur = adapterPosition
                if (cur > 0) move(cur, cur - 1, focusUp = true)
            }
            btnDown.setOnClickListener {
                val cur = adapterPosition
                if (cur < apps.size - 1) move(cur, cur + 1, focusUp = false)
            }

            btnDown.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                    && event.action == KeyEvent.ACTION_DOWN
                    && pos == apps.size - 1) {
                    onSaveRequest(); true
                } else false
            }
        }

        private fun doToggle(pos: Int) {
            val app = apps[pos]
            if (app.packageName in selectedPkgs) selectedPkgs.remove(app.packageName)
            else selectedPkgs.add(app.packageName)
            notifyItemChanged(pos)
            val prev = pos - 1; val next = pos + 1
            if (prev >= 0) notifyItemChanged(prev)
            if (next < apps.size) notifyItemChanged(next)
            onToggled(pos)
        }

        private fun applyGlow(v: View, hasFocus: Boolean) {
            val dp = v.resources.displayMetrics.density
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(if (hasFocus) 0x22E53935.toInt() else 0x00000000)
                setStroke(if (hasFocus) (2 * dp).toInt() else 0,
                    if (hasFocus) 0xFFE53935.toInt() else 0)
                cornerRadius = 6 * dp
            }
            v.background = bg
        }
    }
}
