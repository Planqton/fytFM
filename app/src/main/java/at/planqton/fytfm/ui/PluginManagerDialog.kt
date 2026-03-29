package at.planqton.fytfm.ui

import android.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import at.planqton.fytfm.R

class PluginManagerDialog(
    context: Context,
    private val onImport: () -> Unit,
    private val onExport: (String) -> Unit,
    private val onDelete: (String) -> Unit
) {
    private val dialog: AlertDialog
    val adapter: PluginAdapter

    init {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_plugin_manager, null)

        val recycler = view.findViewById<RecyclerView>(R.id.pluginRecycler)
        recycler.layoutManager = LinearLayoutManager(context)

        adapter = PluginAdapter(
            onExport = onExport,
            onDelete = onDelete
        )
        adapter.refresh()
        recycler.adapter = adapter

        view.findViewById<TextView>(R.id.btnImportPlugin).setOnClickListener {
            onImport()
        }

        view.findViewById<TextView>(R.id.btnClosePluginManager).setOnClickListener {
            dialog.dismiss()
        }

        dialog = AlertDialog.Builder(context, R.style.TransparentDialog)
            .setView(view)
            .create()
    }

    fun show() {
        dialog.show()
    }

    fun dismiss() {
        dialog.dismiss()
    }
}
