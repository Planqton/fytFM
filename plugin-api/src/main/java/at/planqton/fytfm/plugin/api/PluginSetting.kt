package at.planqton.fytfm.plugin.api

/**
 * Typ eines Plugin-Settings.
 */
enum class SettingType {
    TOGGLE,     // Switch (Boolean)
    SLIDER,     // SeekBar (Int)
    DROPDOWN    // Spinner (String aus options-Liste)
}

/**
 * Beschreibt ein einzelnes Setting eines Plugins.
 * Die App rendert daraus automatisch die passende UI.
 */
data class PluginSetting(
    val key: String,
    val label: String,
    val type: SettingType,
    val defaultValue: Any,
    val min: Int = 0,
    val max: Int = 100,
    val options: List<String>? = null
)
