package com.shrine.launcher.ui.settings

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.shrine.launcher.R
import com.shrine.launcher.data.repository.AppRepository
import com.shrine.launcher.data.repository.PreferencesRepository
import com.shrine.launcher.databinding.ActivityRowEditorBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Collections

class RowEditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRowEditorBinding
    private lateinit var repo: PreferencesRepository
    private lateinit var appRepo: AppRepository
    private var rowId: String = ""
    private val appsInRow = mutableListOf<String>()

    companion object {
        const val EXTRA_ROW_ID = "extra_row_id"
        const val REQUEST_PICK_APP = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRowEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repo    = PreferencesRepository.getInstance(this)
        appRepo = AppRepository.getInstance(this)
        rowId   = intent.getStringExtra(EXTRA_ROW_ID) ?: run { finish(); return }

        val row = repo.loadRows().find { it.id == rowId } ?: run { finish(); return }

        binding.etRowTitle.setText(row.title)
        appsInRow.addAll(row.apps)
        setupAppList()

        binding.btnAddAppToRow.setOnClickListener {
            startActivityForResult(
                Intent(this, AppPickerActivity::class.java), REQUEST_PICK_APP
            )
        }

        binding.btnSaveRow.setOnClickListener {
            val updatedTitle = binding.etRowTitle.text.toString().ifBlank { row.title }
            repo.updateRow(row.copy(title = updatedTitle, apps = appsInRow))
            Toast.makeText(this, "Row saved", Toast.LENGTH_SHORT).show()
            finish()
        }

        binding.btnBack.setOnClickListener { finish() }
    }

    private fun setupAppList() {
        val adapter = RowAppsEditAdapter(appsInRow, appRepo) { pkg ->
            appsInRow.remove(pkg)
            setupAppList()
        }
        binding.rvRowApps.layoutManager = LinearLayoutManager(this)
        binding.rvRowApps.adapter       = adapter

        val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder,
                                target: RecyclerView.ViewHolder): Boolean {
                Collections.swap(appsInRow, vh.adapterPosition, target.adapterPosition)
                adapter.notifyItemMoved(vh.adapterPosition, target.adapterPosition)
                return true
            }
            override fun onSwiped(vh: RecyclerView.ViewHolder, dir: Int) {}
        })
        touchHelper.attachToRecyclerView(binding.rvRowApps)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PICK_APP && resultCode == RESULT_OK) {
            val pkg = data?.getStringExtra(AppPickerActivity.RESULT_PACKAGE) ?: return
            if (!appsInRow.contains(pkg)) {
                appsInRow.add(pkg)
                setupAppList()
            }
        }
    }
}
