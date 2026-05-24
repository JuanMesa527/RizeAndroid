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

/**
 * Base de datos Room para gestionar las entidades relacionadas con las sesiones de entrenamiento.
 */
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
    version = 3,
    exportSchema = false
)
abstract class RizeDatabase : RoomDatabase() {

    abstract fun workoutSessionDao(): WorkoutSessionDao
    abstract fun repDao(): RepDao

    /**
     * Objeto companion para gestionar la instancia única de la base de datos.
     */
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
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
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

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE curl_session_details ADD COLUMN attempted_rep_count INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE squat_session_details ADD COLUMN attempted_rep_count INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE bench_session_details ADD COLUMN attempted_rep_count INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
