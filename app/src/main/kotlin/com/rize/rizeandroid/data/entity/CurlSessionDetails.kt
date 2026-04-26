package com.rize.rizeandroid.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

@Entity(
    tableName = "curl_session_details",
    foreignKeys = [
        ForeignKey(
            entity = WorkoutSession::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class CurlSessionDetails(
    @PrimaryKey
    @ColumnInfo(name = "session_id")
    val sessionId: Long,

    @ColumnInfo(name = "avg_peak_flexion_deg")
    val avgPeakFlexionDeg: Double?,

    @ColumnInfo(name = "avg_rom_deg")
    val avgRomDeg: Double?,

    @ColumnInfo(name = "max_shoulder_compensation_deg")
    val maxShoulderCompensationDeg: Double?,

    @ColumnInfo(name = "avg_shoulder_compensation_deg")
    val avgShoulderCompensationDeg: Double?
)
