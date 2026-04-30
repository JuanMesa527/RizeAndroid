package com.rize.rizeandroid.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

@Entity(
    tableName = "squat_session_details",
    foreignKeys = [
        ForeignKey(
            entity = WorkoutSession::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class SquatSessionDetails(
    @PrimaryKey
    @ColumnInfo(name = "session_id")
    val sessionId: Long,

    @ColumnInfo(name = "cvt_percent")
    val cvtPercent: Double?,

    @ColumnInfo(name = "eccentric_peak_velocity_deg_s")
    val eccentricPeakVelocityDegS: Double?,

    @ColumnInfo(name = "min_knee_angle_deg")
    val minKneeAngleDeg: Double?,

    @ColumnInfo(name = "min_hip_angle_deg")
    val minHipAngleDeg: Double?,

    @ColumnInfo(name = "depth_insufficient_count")
    val depthInsufficientCount: Int,

    @ColumnInfo(name = "trunk_lean_risk_count")
    val trunkLeanRiskCount: Int,

    @ColumnInfo(name = "attempted_rep_count")
    val attemptedRepCount: Int = 0
)
