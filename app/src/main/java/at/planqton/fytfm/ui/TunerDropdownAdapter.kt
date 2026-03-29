package at.planqton.fytfm.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import at.planqton.fytfm.tuner.TunerInstance

class TunerDropdownAdapter(
    context: Context,
    private val instances: MutableList<TunerInstance> = mutableListOf()
) : ArrayAdapter<TunerInstance>(context, android.R.layout.simple_spinner_item, instances) {

    init {
        setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
    }

    fun updateInstances(newInstances: List<TunerInstance>) {
        instances.clear()
        instances.addAll(newInstances)
        notifyDataSetChanged()
    }

    fun getPositionForId(id: String): Int {
        return instances.indexOfFirst { it.id == id }
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = super.getView(position, convertView, parent)
        val textView = view.findViewById<TextView>(android.R.id.text1)
        textView.text = instances[position].name
        textView.setTextColor(0xFF333333.toInt())
        textView.textSize = 14f
        return view
    }

    override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = super.getDropDownView(position, convertView, parent)
        val textView = view.findViewById<TextView>(android.R.id.text1)
        textView.text = instances[position].name
        textView.setTextColor(0xFF333333.toInt())
        textView.textSize = 14f
        textView.setPadding(24, 16, 24, 16)
        return view
    }
}
