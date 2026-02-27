package at.planqton.fytfm.data.rdslog

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface EditStringDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(editString: EditString): Long

    @Update
    suspend fun update(editString: EditString)

    @Delete
    suspend fun delete(editString: EditString)

    @Query("DELETE FROM edit_strings WHERE id = :id")
    suspend fun deleteById(id: Long)

    // Get all edit strings for processing - immediate rules (not fallback, enabled only)
    @Query("SELECT * FROM edit_strings WHERE onlyIfNotFound = 0 AND enabled = 1")
    suspend fun getAllImmediate(): List<EditString>

    // Get all edit strings for fallback processing (enabled only)
    @Query("SELECT * FROM edit_strings WHERE onlyIfNotFound = 1 AND enabled = 1")
    suspend fun getAllFallback(): List<EditString>

    // Toggle enabled state
    @Query("UPDATE edit_strings SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    // Get all for display in viewer
    @Query("SELECT * FROM edit_strings ORDER BY timestamp DESC")
    fun getAll(): Flow<List<EditString>>

    // Count
    @Query("SELECT COUNT(*) FROM edit_strings")
    suspend fun getCount(): Int

    // Clear all
    @Query("DELETE FROM edit_strings")
    suspend fun deleteAll(): Int
}
