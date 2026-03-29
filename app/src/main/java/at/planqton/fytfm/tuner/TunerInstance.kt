package at.planqton.fytfm.tuner

import java.util.UUID

/**
 * Repräsentiert eine konfigurierte Tuner-Instanz.
 * Der User kann beliebig viele Instanzen anlegen und jeder
 * ein Plugin zuweisen (z.B. "fyt_fm", "hikity_dab").
 */
data class TunerInstance(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val pluginId: String,
    val sortOrder: Int = 0,
    val lastFrequency: Float = 0f,
    val lastDabServiceLabel: String? = null
)
