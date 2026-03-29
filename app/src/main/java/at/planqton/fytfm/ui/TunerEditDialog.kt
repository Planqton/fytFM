package at.planqton.fytfm.ui

import android.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import at.planqton.fytfm.R
import at.planqton.fytfm.data.PresetRepository
import at.planqton.fytfm.plugin.api.PluginSetting
import at.planqton.fytfm.plugin.api.SettingType
import at.planqton.fytfm.tuner.TunerInstance
import at.planqton.fytfm.tuner.TunerPluginRegistry

class TunerEditDialog(
    private val context: Context,
    private val instance: TunerInstance?,
    private val onSave: (name: String, pluginId: String) -> Unit,
    private val onDelete: (() -> Unit)?
) {
    private val dialog: AlertDialog
    private val settingsWidgets = mutableMapOf<String, Any>() // key -> Widget

    init {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_tuner_edit, null)
        val isNew = instance == null

        view.findViewById<TextView>(R.id.tvEditTitle).text =
            if (isNew) "Neuer Tuner" else "Tuner bearbeiten"

        val etName = view.findViewById<EditText>(R.id.etTunerName)
        etName.setText(instance?.name ?: "")

        // Plugin Spinner
        val availablePlugins = TunerPluginRegistry.getAll()
        val spinnerPlugin = view.findViewById<Spinner>(R.id.spinnerPlugin)
        val pluginNames = availablePlugins.map { it.displayName }
        spinnerPlugin.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, pluginNames).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        val settingsContainer = view.findViewById<LinearLayout>(R.id.pluginSettingsContainer)

        // Gespeicherte Settings laden
        val savedSettings = if (instance != null) {
            PresetRepository(context).loadPluginSettings(instance.id)
        } else emptyMap()

        // Settings für das aktuelle Plugin rendern
        fun renderSettingsForPlugin(pluginId: String) {
            settingsContainer.removeAllViews()
            settingsWidgets.clear()
            val plugin = TunerPluginRegistry.get(pluginId) ?: return
            val settings = plugin.getSettings()
            if (settings.isEmpty()) return

            // Divider oben
            settingsContainer.addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1
                ).apply { topMargin = 8; bottomMargin = 8 }
                setBackgroundColor(0xFFE0E0E0.toInt())
            })

            val titleView = TextView(context).apply {
                text = "Plugin Settings"
                setTextColor(0xFF333333.toInt())
                textSize = 14f
                setPadding(0, 8, 0, 12)
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
            settingsContainer.addView(titleView)

            for (setting in settings) {
                val savedValue = savedSettings[setting.key]
                when (setting.type) {
                    SettingType.TOGGLE -> addToggle(settingsContainer, setting, savedValue)
                    SettingType.SLIDER -> addSlider(settingsContainer, setting, savedValue)
                    SettingType.DROPDOWN -> addDropdown(settingsContainer, setting, savedValue)
                }
            }
        }

        // Initial rendern
        if (instance != null) {
            val idx = availablePlugins.indexOfFirst { it.pluginId == instance.pluginId }
            if (idx >= 0) spinnerPlugin.setSelection(idx)
            renderSettingsForPlugin(instance.pluginId)
        }

        // Bei Plugin-Wechsel Settings neu rendern
        spinnerPlugin.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                renderSettingsForPlugin(availablePlugins[pos].pluginId)
                updateDeviceStatus(view, availablePlugins[pos].pluginId)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Device Status
        updateDeviceStatus(view, instance?.pluginId)

        // Delete Button
        val btnDelete = view.findViewById<TextView>(R.id.btnDeleteTuner)
        if (isNew || onDelete == null) {
            btnDelete.visibility = View.GONE
        } else {
            btnDelete.setOnClickListener {
                onDelete.invoke()
                dialog.dismiss()
            }
        }

        // Save Button
        view.findViewById<TextView>(R.id.btnSaveTuner).setOnClickListener {
            val name = etName.text.toString().trim()
            if (name.isEmpty()) {
                etName.error = "Name eingeben"
                return@setOnClickListener
            }
            if (availablePlugins.isEmpty()) return@setOnClickListener
            val selectedPlugin = availablePlugins[spinnerPlugin.selectedItemPosition]

            // Settings sammeln und speichern
            val tunerId = instance?.id ?: ""
            if (tunerId.isNotEmpty()) {
                val collectedSettings = collectSettings(selectedPlugin.pluginId)
                PresetRepository(context).savePluginSettings(tunerId, collectedSettings)
                selectedPlugin.applySettings(collectedSettings)
            }

            onSave(name, selectedPlugin.pluginId)
            dialog.dismiss()
        }

        dialog = AlertDialog.Builder(context, R.style.TransparentDialog)
            .setView(view)
            .create()
    }

    private fun addToggle(container: LinearLayout, setting: PluginSetting, savedValue: Any?) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 8 }
            gravity = android.view.Gravity.CENTER_VERTICAL
            minimumHeight = (48 * context.resources.displayMetrics.density).toInt()
        }

        val switch = SwitchCompat(context).apply {
            layoutParams = LinearLayout.LayoutParams(52.dp(), 32.dp())
            isChecked = (savedValue as? Boolean) ?: (setting.defaultValue as? Boolean) ?: false
        }
        settingsWidgets[setting.key] = switch

        val label = TextView(context).apply {
            text = setting.label
            setTextColor(0xFF333333.toInt())
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = (16 * context.resources.displayMetrics.density).toInt()
            }
        }

        row.addView(switch)
        row.addView(label)
        container.addView(row)
    }

    private fun addSlider(container: LinearLayout, setting: PluginSetting, savedValue: Any?) {
        val label = TextView(context).apply {
            val value = (savedValue as? Int) ?: (setting.defaultValue as? Int) ?: setting.min
            text = "${setting.label}: $value"
            setTextColor(0xFF333333.toInt())
            textSize = 14f
            setPadding(0, 4, 0, 4)
        }
        container.addView(label)

        val seekBar = SeekBar(context).apply {
            max = setting.max - setting.min
            progress = ((savedValue as? Int) ?: (setting.defaultValue as? Int) ?: setting.min) - setting.min
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 8 }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    label.text = "${setting.label}: ${progress + setting.min}"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        settingsWidgets[setting.key] = seekBar
        container.addView(seekBar)
    }

    private fun addDropdown(container: LinearLayout, setting: PluginSetting, savedValue: Any?) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 8 }
            gravity = android.view.Gravity.CENTER_VERTICAL
            minimumHeight = (48 * context.resources.displayMetrics.density).toInt()
        }

        val label = TextView(context).apply {
            text = setting.label
            setTextColor(0xFF333333.toInt())
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val options = setting.options ?: return
        val spinner = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, options).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            val savedStr = savedValue as? String ?: setting.defaultValue as? String
            val idx = options.indexOf(savedStr)
            if (idx >= 0) setSelection(idx)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        settingsWidgets[setting.key] = spinner

        row.addView(label)
        row.addView(spinner)
        container.addView(row)
    }

    private fun collectSettings(pluginId: String): Map<String, Any> {
        val plugin = TunerPluginRegistry.get(pluginId) ?: return emptyMap()
        val result = mutableMapOf<String, Any>()

        for (setting in plugin.getSettings()) {
            val widget = settingsWidgets[setting.key] ?: continue
            when (setting.type) {
                SettingType.TOGGLE -> result[setting.key] = (widget as SwitchCompat).isChecked
                SettingType.SLIDER -> result[setting.key] = (widget as SeekBar).progress + setting.min
                SettingType.DROPDOWN -> {
                    val spinner = widget as Spinner
                    val options = setting.options ?: continue
                    if (spinner.selectedItemPosition in options.indices) {
                        result[setting.key] = options[spinner.selectedItemPosition]
                    }
                }
            }
        }
        return result
    }

    private fun updateDeviceStatus(view: View, pluginId: String?) {
        val statusIndicator = view.findViewById<View>(R.id.statusIndicator)
        val tvDeviceStatus = view.findViewById<TextView>(R.id.tvDeviceStatus)

        if (pluginId == null) {
            statusIndicator.setBackgroundResource(R.drawable.status_indicator_red)
            tvDeviceStatus.text = "Noch nicht konfiguriert"
            tvDeviceStatus.setTextColor(0xFF999999.toInt())
            return
        }

        val plugin = TunerPluginRegistry.get(pluginId)
        if (plugin == null) {
            statusIndicator.setBackgroundResource(R.drawable.status_indicator_red)
            tvDeviceStatus.text = "Plugin nicht verfügbar"
            tvDeviceStatus.setTextColor(0xFFC52322.toInt())
        } else if (!plugin.isHardwareAvailable()) {
            statusIndicator.setBackgroundResource(R.drawable.status_indicator_red)
            tvDeviceStatus.text = "Kein Gerät erkannt"
            tvDeviceStatus.setTextColor(0xFFC52322.toInt())
        } else if (plugin.isPoweredOn) {
            statusIndicator.setBackgroundResource(R.drawable.status_indicator_green)
            tvDeviceStatus.text = "Gerät verbunden"
            tvDeviceStatus.setTextColor(0xFF2E7D32.toInt())
        } else {
            statusIndicator.setBackgroundResource(R.drawable.status_indicator_green)
            tvDeviceStatus.text = "Gerät erkannt"
            tvDeviceStatus.setTextColor(0xFF666666.toInt())
        }
    }

    private fun Int.dp(): Int = (this * context.resources.displayMetrics.density).toInt()

    fun show() {
        dialog.show()
    }
}
