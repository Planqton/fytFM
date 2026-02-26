package at.planqton.fytfm.data.rdslog

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [RdsLogEntry::class],
    version = 1,
    exportSchema = false
)
abstract class RdsDatabase : RoomDatabase() {

    abstract fun rdsLogDao(): RdsLogDao

    companion object {
        @Volatile
        private var INSTANCE: RdsDatabase? = null

        fun getInstance(context: Context): RdsDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    RdsDatabase::class.java,
                    "rds_log.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
