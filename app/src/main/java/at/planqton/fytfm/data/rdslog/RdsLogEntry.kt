package at.planqton.fytfm.data.rdslog

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "rds_log",
    indices = [
        Index(value = ["timestamp"]),
        Index(value = ["frequency"]),
        Index(value = ["pi"])
    ]
)
data class RdsLogEntry(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val timestamp: Long,
    val frequency: Float,
    val isAM: Boolean = false,

    // RDS fields
    val ps: String?,
    val rt: String?,
    val pi: Int,
    val pty: Int,
    val tp: Int,
    val ta: Int,
    val rssi: Int,
    val afList: String?,

    // Event type: null = RT change, "STATION_CHANGE" = frequency change
    val eventType: String? = null
)
