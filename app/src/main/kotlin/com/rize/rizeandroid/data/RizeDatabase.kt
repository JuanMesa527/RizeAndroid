package com.rize.rizeandroid.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.rize.rizeandroid.data.dao.RepDao
import com.rize.rizeandroid.data.dao.WorkoutSessionDao
import com.rize.rizeandroid.data.entity.BenchSessionDetails
import com.rize.rizeandroid.data.entity.CurlSessionDetails
import com.rize.rizeandroid.data.entity.RepBenchDetails
import com.rize.rizeandroid.data.entity.RepCurlDetails
import com.rize.rizeandroid.data.entity.RepSquatDetails
import com.rize.rizeandroid.data.entity.SessionRep
import com.rize.rizeandroid.data.entity.SquatSessionDetails
import com.rize.rizeandroid.data.entity.WorkoutSession

@Database(
    entities = [
        WorkoutSession::class,
        SquatSessionDetails::class,
        CurlSessionDetails::class,
        BenchSessionDetails::class,
        SessionRep::class,
        RepSquatDetails::class,
        RepCurlDetails::class,
        RepBenchDetails::class
    ],
    version = 2,
    exportSchema = false
)
abstract class RizeDatabase : RoomDatabase() {

    abstract fun workoutSessionDao(): WorkoutSessionDao
    abstract fun repDao(): RepDao

    companion object {
        private const val DB_NAME = "rize.db"

        @Volatile
        private var INSTANCE: RizeDatabase? = null

        fun getInstance(context: Context): RizeDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    RizeDatabase::class.java,
                    DB_NAME
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { INSTANCE = it }
            }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // No-op: version 2 reemplazo una recreacion destructiva temporal
                // sin cambios de esquema persistentes.
            }
        }
    }
}
