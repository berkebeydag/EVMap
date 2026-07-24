package com.berke.ioniqscope.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        PerfRunEntity::class,
        TripEntity::class,
        TripSampleEntity::class,
        AuxVoltageEntity::class
    ],
    version = 2,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun perfRunDao(): PerfRunDao
    abstract fun tripDao(): TripDao
    abstract fun auxVoltageDao(): AuxVoltageDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        /**
         * v1 -> v2: adds the 12V auxiliary battery trend table.
         *
         * A real migration rather than destructive fallback: the whole value of this
         * table is that it accumulates over months, and run history predates it.
         * Wiping the database on upgrade would defeat the feature it is being added for.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `aux_voltage` (
                        `id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        `at` INTEGER NOT NULL,
                        `volts` REAL NOT NULL,
                        `at_session_start` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_aux_voltage_at` ON `aux_voltage` (`at`)")
            }
        }

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "ioniqscope.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance = it }
            }
    }
}
