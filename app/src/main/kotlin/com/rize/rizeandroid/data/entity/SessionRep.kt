package com.rize.rizeandroid.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Una repetición individual dentro de una sesión. Campos comunes a los 3
 * ejercicios (rep_number, ROM, ángulos pico/valle, velocidad). Detalles
 * específicos en `rep_*_details`.
 */
@Entity(
    tableName = "session_rep",
    foreignKeys = [
        ForeignKey(
            entity = WorkoutSession::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["session_id"])]
)
data class SessionRep(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "session_id")
    val sessionId: Long,

    @ColumnInfo(name = "rep_number")
    val repNumber: Int,

    @ColumnInfo(name = "timestamp_offset_ms")
    val timestampOffsetMs: Long,

    @ColumnInfo(name = "peak_angle_deg")
    val peakAngleDeg: Double?,

    @ColumnInfo(name = "valley_angle_deg")
    val valleyAngleDeg: Double?,

    @ColumnInfo(name = "rom_deg")
    val romDeg: Double?,

    @ColumnInfo(name = "peak_velocity_deg_s")
    val peakVelocityDegS: Double?,

    @ColumnInfo(name = "form_quality")
    val formQuality: String
)
