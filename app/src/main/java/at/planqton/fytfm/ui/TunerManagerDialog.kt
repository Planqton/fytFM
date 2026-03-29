package at.planqton.fytfm.ui

import android.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import at.planqton.fytfm.R
import at.planqton.fytfm.tuner.TunerManager

class TunerManagerDialog(
    private val context: Context,
    private val tunerManager: TunerManager,
    private val onChanged: () -> Unit
) {
    private val dialog: AlertDialog
    private val adapter: TunerInstanceAdapter

    init {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_tuner_manager, null)

        val recycler = view.findViewById<RecyclerView>(R.id.tunerRecycler)
        recycler.layoutManager = LinearLayoutManager(context)

        adapter = TunerInstanceAdapter(
            instances = tunerManager.getInstances().toMutableList(),
            onClick = { instance ->
                // Bearbeitungsdialog öffnen
                TunerEditDialog(
                    context = context,
                    instance = instance,
                    onSave = { name, pluginId ->
                        tunerManager.updateInstance(instance.id, name = name, pluginId = pluginId)
                        adapter.updateInstances(tunerManager.getInstances())
                        onChanged()
                    },
                    onDelete = {
                        tunerManager.deleteInstance(instance.id)
                        adapter.updateInstances(tunerManager.getInstances())
                        onChanged()
                    }
                ).show()
            }
        )
        recycler.adapter = adapter

        // Neuer Tuner
        view.findViewById<TextView>(R.id.btnAddTuner).setOnClickListener {
            TunerEditDialog(
                context = context,
                instance = null,
                onSave = { name, pluginId ->
                    tunerManager.addInstance(name, pluginId)
                    adapter.updateInstances(tunerManager.getInstances())
                    onChanged()
                },
                onDelete = null
            ).show()
        }

        view.findViewById<TextView>(R.id.btnCloseTunerManager).setOnClickListener {
            dialog.dismiss()
        }

        dialog = AlertDialog.Builder(context, R.style.TransparentDialog)
            .setView(view)
            .create()
    }

    fun show() {
        dialog.show()
    }
}
