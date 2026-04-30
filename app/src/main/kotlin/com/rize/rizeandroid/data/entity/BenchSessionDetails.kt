package com.rize.rizeandroid.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

@Entity(
    tableName = "bench_session_details",
    foreignKeys = [
        ForeignKey(
            entity = WorkoutSession::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class BenchSessionDetails(
    @PrimaryKey
    @ColumnInfo(name = "session_id")
    val sessionId: Long,

    @ColumnInfo(name = "avg_grip_width_ratio")
    val avgGripWidthRatio: Double?,

    @ColumnInfo(name = "avg_shoulder_abduction_deg")
    val avgShoulderAbductionDeg: Double?,

    @ColumnInfo(name = "avg_bilateral_asymmetry_deg")
    val avgBilateralAsymmetryDeg: Double?,

    @ColumnInfo(name = "extension_incomplete_count")
    val extensionIncompleteCount: Int,

    @ColumnInfo(name = "sticking_period_count")
    val stickingPeriodCount: Int,

    @ColumnInfo(name = "grip_too_wide_count")
    val gripTooWideCount: Int,

    @ColumnInfo(name = "bilateral_asymmetry_count")
    val bilateralAsymmetryCount: Int,

    @ColumnInfo(name = "depth_insufficient_count")
    val depthInsufficientCount: Int,

    @ColumnInfo(name = "attempted_rep_count")
    val attemptedRepCount: Int = 0
)
