package at.planqton.fytfm.ui

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.Window
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import at.planqton.fytfm.R
import at.planqton.fytfm.data.PresetRepository
import at.planqton.fytfm.scanner.RadioScanner

/**
 * Scan-Ergebnis Datenklasse
 */
data class ScanConfig(
    val useNativeMethod: Boolean,
    val autoFilter: Boolean,
    val filterMode: RadioScanner.FilterMode,
    val rdsTimeoutSeconds: Int,
    val quickScan: Boolean = false
)

class ScanOptionsDialog(
    context: Context,
    private val presetRepository: PresetRepository,
    private val onStart: (config: ScanConfig) -> Unit
) : Dialog(context) {

    private lateinit var rgScanMethod: RadioGroup
    private lateinit var rbQuickScan: RadioButton
    private lateinit var rbNative: RadioButton
    private lateinit var rbExperimental: RadioButton
    private lateinit var cbRequirePs: CheckBox
    private lateinit var cbRequirePi: CheckBox
    private lateinit var rgFilterLogic: RadioGroup
    private lateinit var rbFilterAnd: RadioButton
    private lateinit var rbFilterOr: RadioButton
    private lateinit var rdsTimeoutContainer: LinearLayout
    private lateinit var tvRdsTimeout: TextView
    private lateinit var seekRdsTimeout: SeekBar
    private lateinit var switchOverwriteFavorites: Switch
    private lateinit var btnStart: Button
    private lateinit var btnStartAndFilter: Button
    private lateinit var btnCancel: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.dialog_scan_options)

        // Dialog-Breite anpassen
        window?.setLayout(
            (context.resources.displayMetrics.widthPixels * 0.9).toInt(),
            android.view.WindowManager.LayoutParams.WRAP_CONTENT
        )

        initViews()
        setupListeners()
        updateFilterVisibility()
    }

    private fun initViews() {
        rgScanMethod = findViewById(R.id.rgScanMethod)
        rbQuickScan = findViewById(R.id.rbQuickScan)
        rbNative = findViewById(R.id.rbNative)
        rbExperimental = findViewById(R.id.rbExperimental)
        cbRequirePs = findViewById(R.id.cbRequirePs)
        cbRequirePi = findViewById(R.id.cbRequirePi)
        rgFilterLogic = findViewById(R.id.rgFilterLogic)
        rbFilterAnd = findViewById(R.id.rbFilterAnd)
        rbFilterOr = findViewById(R.id.rbFilterOr)
        rdsTimeoutContainer = findViewById(R.id.rdsTimeoutContainer)
        tvRdsTimeout = findViewById(R.id.tvRdsTimeout)
        seekRdsTimeout = findViewById(R.id.seekRdsTimeout)
        switchOverwriteFavorites = findViewById(R.id.switchOverwriteFavorites)
        btnStart = findViewById(R.id.btnStart)
        btnStartAndFilter = findViewById(R.id.btnStartAndFilter)
        btnCancel = findViewById(R.id.btnCancel)

        // Aktuelle Werte laden
        switchOverwriteFavorites.isChecked = presetRepository.isOverwriteFavorites()
        seekRdsTimeout.progress = 8
        updateTimeoutLabel(8)
    }

    private fun setupListeners() {
        // Scan-Methode Änderungen
        rgScanMethod.setOnCheckedChangeListener { _, _ -> updateFilterVisibility() }

        // Filter-Checkbox Änderungen
        cbRequirePs.setOnCheckedChangeListener { _, _ -> updateFilterVisibility() }
        cbRequirePi.setOnCheckedChangeListener { _, _ -> updateFilterVisibility() }

        // Timeout Slider
        seekRdsTimeout.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateTimeoutLabel(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Starten - Scan (bei Quick Scan direkt hinzufügen)
        btnStart.setOnClickListener {
            saveSettings()
            dismiss()

            if (rbQuickScan.isChecked) {
                // Quick Scan - Native + direkt hinzufügen
                onStart(ScanConfig(
                    useNativeMethod = true,
                    autoFilter = false,
                    filterMode = RadioScanner.FilterMode.NONE,
                    rdsTimeoutSeconds = 0,
                    quickScan = true
                ))
            } else {
                // Normaler Scan ohne Filter
                onStart(ScanConfig(
                    useNativeMethod = rbNative.isChecked || rbQuickScan.isChecked,
                    autoFilter = false,
                    filterMode = getFilterMode(),
                    rdsTimeoutSeconds = seekRdsTimeout.progress
                ))
            }
        }

        // Starten + Filter - Scan mit automatischem RDS-Filter
        btnStartAndFilter.setOnClickListener {
            saveSettings()
            dismiss()
            onStart(ScanConfig(
                useNativeMethod = rbNative.isChecked,
                autoFilter = true,
                filterMode = getFilterMode(),
                rdsTimeoutSeconds = seekRdsTimeout.progress
            ))
        }

        btnCancel.setOnClickListener {
            dismiss()
        }
    }

    private fun updateFilterVisibility() {
        val isQuickScan = rbQuickScan.isChecked
        val hasFilter = cbRequirePs.isChecked || cbRequirePi.isChecked
        val hasBothFilters = cbRequirePs.isChecked && cbRequirePi.isChecked

        // Bei Quick Scan: Filter-Optionen deaktivieren
        cbRequirePs.isEnabled = !isQuickScan
        cbRequirePi.isEnabled = !isQuickScan
        cbRequirePs.alpha = if (isQuickScan) 0.5f else 1.0f
        cbRequirePi.alpha = if (isQuickScan) 0.5f else 1.0f

        // UND/ODER nur zeigen wenn beide Checkboxen aktiv und nicht Quick Scan
        rgFilterLogic.visibility = if (hasBothFilters && !isQuickScan) View.VISIBLE else View.GONE

        // Timeout-Slider zeigen wenn mindestens ein Filter aktiv und nicht Quick Scan
        rdsTimeoutContainer.visibility = if (hasFilter && !isQuickScan) View.VISIBLE else View.GONE

        // "Starten + Filter" Button nur aktiv wenn Filter ausgewählt und nicht Quick Scan
        val canFilter = hasFilter && !isQuickScan
        btnStartAndFilter.isEnabled = canFilter
        btnStartAndFilter.alpha = if (canFilter) 1.0f else 0.5f
    }

    private fun updateTimeoutLabel(seconds: Int) {
        tvRdsTimeout.text = context.getString(R.string.rds_wait_time, seconds)
    }

    private fun getFilterMode(): RadioScanner.FilterMode {
        val requirePs = cbRequirePs.isChecked
        val requirePi = cbRequirePi.isChecked

        return when {
            requirePs && requirePi && rbFilterAnd.isChecked -> RadioScanner.FilterMode.REQUIRE_PS_AND_PI
            requirePs && requirePi && rbFilterOr.isChecked -> RadioScanner.FilterMode.REQUIRE_PS_OR_PI
            requirePs -> RadioScanner.FilterMode.REQUIRE_PS
            requirePi -> RadioScanner.FilterMode.REQUIRE_PI
            else -> RadioScanner.FilterMode.NONE
        }
    }

    private fun saveSettings() {
        presetRepository.setOverwriteFavorites(switchOverwriteFavorites.isChecked)
    }
}
