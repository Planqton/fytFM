package at.planqton.fytfm.data.logo

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.RadioButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import at.planqton.fytfm.R

class LogoTemplateAdapter(
    private var templates: List<RadioLogoTemplate>,
    private var selectedName: String?,
    private val onSelect: (RadioLogoTemplate) -> Unit,
    private val onEdit: (RadioLogoTemplate) -> Unit,
    private val onExport: (RadioLogoTemplate) -> Unit,
    private val onDelete: (RadioLogoTemplate) -> Unit
) : RecyclerView.Adapter<LogoTemplateAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val radioTemplate: RadioButton = view.findViewById(R.id.radioTemplate)
        val textTemplateName: TextView = view.findViewById(R.id.textTemplateName)
        val textTemplateInfo: TextView = view.findViewById(R.id.textTemplateInfo)
        val btnEditTemplate: ImageButton = view.findViewById(R.id.btnEditTemplate)
        val btnExportTemplate: ImageButton = view.findViewById(R.id.btnExportTemplate)
        val btnDeleteTemplate: ImageButton = view.findViewById(R.id.btnDeleteTemplate)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_logo_template, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val template = templates[position]

        holder.textTemplateName.text = template.name
        holder.textTemplateInfo.text = "${template.stations.size} Sender"
        holder.radioTemplate.isChecked = template.name == selectedName

        holder.itemView.setOnClickListener {
            val oldSelected = selectedName
            selectedName = template.name

            // Update UI
            templates.forEachIndexed { index, t ->
                if (t.name == oldSelected || t.name == selectedName) {
                    notifyItemChanged(index)
                }
            }

            onSelect(template)
        }

        holder.btnEditTemplate.setOnClickListener {
            onEdit(template)
        }

        holder.btnExportTemplate.setOnClickListener {
            onExport(template)
        }

        holder.btnDeleteTemplate.setOnClickListener {
            onDelete(template)
        }
    }

    override fun getItemCount() = templates.size

    fun updateTemplates(newTemplates: List<RadioLogoTemplate>, newSelectedName: String?) {
        templates = newTemplates
        selectedName = newSelectedName
        notifyDataSetChanged()
    }
}
