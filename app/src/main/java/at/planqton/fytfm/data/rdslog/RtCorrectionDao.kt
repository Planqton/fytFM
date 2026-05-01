package at.planqton.fytfm.data.rdslog

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RtCorrectionDao {

    @Insert
    suspend fun insert(correction: RtCorrection): Long

    @Delete
    suspend fun delete(correction: RtCorrection)

    @Query("DELETE FROM rt_corrections WHERE id = :id")
    suspend fun deleteById(id: Long)

    // Check if an RT should be ignored
    @Query("SELECT EXISTS(SELECT 1 FROM rt_corrections WHERE rtNormalized = :rtNormalized AND type = 'IGNORED')")
    suspend fun isRtIgnored(rtNormalized: String): Boolean

    // Get all skipped trackIds for an RT
    @Query("SELECT skipTrackId FROM rt_corrections WHERE rtNormalized = :rtNormalized AND type = 'SKIP_TRACK' AND skipTrackId IS NOT NULL")
    suspend fun getSkippedTrackIds(rtNormalized: String): List<String>

    // Check if a specific track is skipped for an RT
    @Query("SELECT EXISTS(SELECT 1 FROM rt_corrections WHERE rtNormalized = :rtNormalized AND type = 'SKIP_TRACK' AND skipTrackId = :trackId)")
    suspend fun isTrackSkipped(rtNormalized: String, trackId: String): Boolean

    // Get all corrections for viewer
    @Query("SELECT * FROM rt_corrections ORDER BY timestamp DESC")
    fun getAllCorrections(): Flow<List<RtCorrection>>

    // Get ignored RTs only
    @Query("SELECT * FROM rt_corrections WHERE type = 'IGNORED' ORDER BY timestamp DESC")
    fun getIgnoredRts(): Flow<List<RtCorrection>>

    // Get skip track corrections only
    @Query("SELECT * FROM rt_corrections WHERE type = 'SKIP_TRACK' ORDER BY timestamp DESC")
    fun getSkipTrackCorrections(): Flow<List<RtCorrection>>

    // Count corrections
    @Query("SELECT COUNT(*) FROM rt_corrections")
    suspend fun getCount(): Int

    // Clear all corrections
    @Query("DELETE FROM rt_corrections")
    suspend fun deleteAll(): Int
}
