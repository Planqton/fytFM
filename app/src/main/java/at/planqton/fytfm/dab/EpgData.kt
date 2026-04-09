package at.planqton.fytfm.dab

/**
 * Container für EPG-Daten eines Services.
 */
data class EpgData(
    val serviceId: Int,
    val serviceName: String,
    val currentItem: EpgItem?,
    val upcomingItems: List<EpgItem>,
    val lastUpdated: Long = System.currentTimeMillis()
) {
    /**
     * Prüft ob EPG-Daten vorhanden sind
     */
    fun hasEpgData(): Boolean = currentItem != null || upcomingItems.isNotEmpty()

    /**
     * Alle Einträge (aktuell + kommend)
     */
    fun getAllItems(): List<EpgItem> {
        return listOfNotNull(currentItem) + upcomingItems
    }

    /**
     * Anzahl der verfügbaren Einträge
     */
    fun getItemCount(): Int = getAllItems().size
}
