package at.planqton.fytfm.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import at.planqton.fytfm.R
import at.planqton.fytfm.tuner.TunerPluginRegistry

class PluginAdapter(
    private val onExport: (String) -> Unit,
    private val onDelete: (String) -> Unit
) : RecyclerView.Adapter<PluginAdapter.ViewHolder>() {

    private var items: List<TunerPluginRegistry.RegisteredPlugin> = emptyList()

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tvPluginName)
        val tvId: TextView = view.findViewById(R.id.tvPluginId)
        val tvBuiltIn: TextView = view.findViewById(R.id.tvBuiltIn)
        val btnExport: ImageButton = view.findViewById(R.id.btnExportPlugin)
        val btnDelete: ImageButton = view.findViewById(R.id.btnDeletePlugin)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_plugin, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val reg = items[position]
        val plugin = reg.plugin

        holder.tvName.text = plugin.displayName
        holder.tvId.text = "${plugin.pluginId} · ${plugin.type.name}"

        if (reg.builtIn) {
            holder.tvBuiltIn.visibility = View.VISIBLE
            holder.btnExport.visibility = View.GONE
            holder.btnDelete.visibility = View.GONE
        } else {
            holder.tvBuiltIn.visibility = View.GONE
            holder.btnExport.visibility = View.VISIBLE
            holder.btnDelete.visibility = View.VISIBLE

            holder.btnExport.setOnClickListener { onExport(plugin.pluginId) }
            holder.btnDelete.setOnClickListener { onDelete(plugin.pluginId) }
        }
    }

    override fun getItemCount(): Int = items.size

    fun refresh() {
        items = TunerPluginRegistry.getAllRegistered()
        notifyDataSetChanged()
    }
}
