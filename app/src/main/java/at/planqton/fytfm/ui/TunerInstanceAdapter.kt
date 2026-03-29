package at.planqton.fytfm.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import at.planqton.fytfm.R
import at.planqton.fytfm.tuner.TunerInstance
import at.planqton.fytfm.tuner.TunerPluginRegistry

class TunerInstanceAdapter(
    private val instances: MutableList<TunerInstance>,
    private val onClick: (TunerInstance) -> Unit
) : RecyclerView.Adapter<TunerInstanceAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val statusIndicator: View = view.findViewById(R.id.statusIndicator)
        val tvName: TextView = view.findViewById(R.id.tvTunerName)
        val tvPlugin: TextView = view.findViewById(R.id.tvTunerPlugin)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_tuner_instance, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val instance = instances[position]
        val plugin = TunerPluginRegistry.get(instance.pluginId)

        holder.tvName.text = instance.name
        holder.tvPlugin.text = plugin?.displayName ?: instance.pluginId

        // Status: grün wenn Hardware verfügbar, rot wenn nicht
        val statusRes = if (plugin != null && plugin.isHardwareAvailable()) {
            R.drawable.status_indicator_green
        } else {
            R.drawable.status_indicator_red
        }
        holder.statusIndicator.setBackgroundResource(statusRes)

        holder.itemView.setOnClickListener { onClick(instance) }
    }

    override fun getItemCount(): Int = instances.size

    fun updateInstances(newInstances: List<TunerInstance>) {
        instances.clear()
        instances.addAll(newInstances)
        notifyDataSetChanged()
    }
}
