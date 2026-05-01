package at.planqton.fytfm.data.rdslog

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RdsLogDao {

    @Insert
    suspend fun insert(entry: RdsLogEntry): Long

    // Get all entries, newest first
    @Query("SELECT * FROM rds_log ORDER BY timestamp DESC")
    fun getAllEntriesFlow(): Flow<List<RdsLogEntry>>

    // Get entries for specific frequency (with tolerance ±0.05 MHz)
    @Query("""
        SELECT * FROM rds_log
        WHERE frequency BETWEEN :minFreq AND :maxFreq
        ORDER BY timestamp DESC
    """)
    fun getEntriesByFrequency(minFreq: Float, maxFreq: Float): Flow<List<RdsLogEntry>>

    // Get entries by PI code
    @Query("SELECT * FROM rds_log WHERE pi = :piCode ORDER BY timestamp DESC")
    fun getEntriesByPi(piCode: Int): Flow<List<RdsLogEntry>>

    // Search RT text
    @Query("SELECT * FROM rds_log WHERE rt LIKE '%' || :searchQuery || '%' ORDER BY timestamp DESC")
    fun searchRt(searchQuery: String): Flow<List<RdsLogEntry>>

    // Get entry count
    @Query("SELECT COUNT(*) FROM rds_log")
    suspend fun getEntryCount(): Int

    // Delete entries older than timestamp
    @Query("DELETE FROM rds_log WHERE timestamp < :olderThan")
    suspend fun deleteOlderThan(olderThan: Long): Int

    // Clear all entries
    @Query("DELETE FROM rds_log")
    suspend fun deleteAll(): Int

    // Get distinct frequencies with counts
    @Query("""
        SELECT frequency, COUNT(*) as count, MAX(ps) as latestPs
        FROM rds_log
        GROUP BY CAST(frequency * 10 AS INTEGER)
        ORDER BY frequency
    """)
    fun getDistinctFrequencies(): Flow<List<FrequencyStats>>
}

data class FrequencyStats(
    val frequency: Float,
    val count: Int,
    val latestPs: String?
)
