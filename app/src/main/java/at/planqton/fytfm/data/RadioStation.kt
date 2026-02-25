package at.planqton.fytfm.data

data class RadioStation(
    val frequency: Float,
    val name: String? = null,
    val rssi: Int = 0,
    val isAM: Boolean = false,
    val isFavorite: Boolean = false
) {
    fun getDisplayFrequency(): String {
        return if (isAM) {
            "AM ${frequency.toInt()}"
        } else {
            "FM %.2f".format(frequency)
        }
    }

    fun getDisplayName(): String? = name?.takeIf { it.isNotBlank() }
}
