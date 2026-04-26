package com.rize.rizeandroid.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

@Entity(
    tableName = "rep_bench_details",
    foreignKeys = [
        ForeignKey(
            entity = SessionRep::class,
            parentColumns = ["id"],
            childColumns = ["rep_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class RepBenchDetails(
    @PrimaryKey
    @ColumnInfo(name = "rep_id")
    val repId: Long,

    @ColumnInfo(name = "min_elbow_angle_deg")
    val minElbowAngleDeg: Double?,

    @ColumnInfo(name = "bilateral_asymmetry_deg")
    val bilateralAsymmetryDeg: Double?,

    @ColumnInfo(name = "shoulder_abduction_deg")
    val shoulderAbductionDeg: Double?,

    @ColumnInfo(name = "extension_incomplete_deg")
    val extensionIncompleteDeg: Double?,

    @ColumnInfo(name = "grip_width_ratio")
    val gripWidthRatio: Double?,

    @ColumnInfo(name = "sticking_period_detected")
    val stickingPeriodDetected: Boolean,

    @ColumnInfo(name = "grip_too_wide")
    val gripTooWide: Boolean,

    @ColumnInfo(name = "bilateral_asymmetry")
    val bilateralAsymmetry: Boolean,

    @ColumnInfo(name = "depth_insufficient")
    val depthInsufficient: Boolean,

    @ColumnInfo(name = "extension_incomplete")
    val extensionIncomplete: Boolean
)
