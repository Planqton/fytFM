package at.planqton.fytfm.dab

import at.planqton.fytfm.data.RadioStation

data class DabStation(
    val serviceId: Int,
    val ensembleId: Int,
    val serviceLabel: String,
    val ensembleLabel: String,
    val ensembleFrequencyKHz: Int
) {
    fun toRadioStation(): RadioStation {
        return RadioStation(
            frequency = ensembleFrequencyKHz / 1000f,
            name = serviceLabel,
            rssi = 0,
            isAM = false,
            isDab = true,
            isFavorite = false,
            syncName = false,
            serviceId = serviceId,
            ensembleId = ensembleId,
            ensembleLabel = ensembleLabel
        )
    }
}
