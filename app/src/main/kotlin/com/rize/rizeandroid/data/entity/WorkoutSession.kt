package com.rize.rizeandroid.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Sesión de entrenamiento. Una fila por sesión (independientemente del ejercicio).
 * Los detalles específicos del ejercicio viven en `*_session_details` y las
 * repeticiones individuales en `session_rep` + `rep_*_details`.
 */
@Entity(
    tableName = "workout_session",
    indices = [
        Index(value = ["exercise_type"]),
        Index(value = ["started_at"])
    ]
)
data class WorkoutSession(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "exercise_type")
    val exerciseType: String,

    @ColumnInfo(name = "exercise_name")
    val exerciseName: String,

    @ColumnInfo(name = "started_at")
    val startedAt: Long,

    @ColumnInfo(name = "ended_at")
    val endedAt: Long,

    @ColumnInfo(name = "duration_seconds")
    val durationSeconds: Int,

    @ColumnInfo(name = "total_reps")
    val totalReps: Int,

    @ColumnInfo(name = "auto_saved")
    val autoSaved: Boolean,

    @ColumnInfo(name = "concentric_peak_velocity_deg_s")
    val concentricPeakVelocityDegS: Double?,

    @ColumnInfo(name = "velocity_loss_percent")
    val velocityLossPercent: Double?,

    @ColumnInfo(name = "technical_error_level")
    val technicalErrorLevel: String,

    @ColumnInfo(name = "avg_error_magnitude")
    val avgErrorMagnitude: Double?
) {
    companion object {
        const val TYPE_SQUAT = "squat"
        const val TYPE_CURL = "curl"
        const val TYPE_BENCH = "bench"
    }
}
