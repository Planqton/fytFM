package at.planqton.fytfm.data

data class RadioStation(
    val frequency: Float,
    val name: String? = null,
    val rssi: Int = 0,
    val isAM: Boolean = false,
    val isDab: Boolean = false,
    val isFavorite: Boolean = false,
    val syncName: Boolean = true,  // Auto-Sync mit RDS PS
    val serviceId: Int = 0,        // DAB Service ID
    val ensembleId: Int = 0,       // DAB Ensemble ID
    val ensembleLabel: String? = null, // DAB Ensemble Label
    val pi: Int = 0,               // RDS Programme Identification (FM/AM)
    val logoPath: String? = null   // Absoluter Pfad zur Logo-Datei (per-Station)
) {
    fun getDisplayFrequency(): String {
        return when {
            isDab -> name ?: ensembleLabel ?: "DAB+"
            isAM -> "AM ${frequency.toInt()}"
            else -> "FM %.2f".format(frequency)
        }
    }

    fun getDisplayName(): String? = name?.takeIf { it.isNotBlank() }
}
