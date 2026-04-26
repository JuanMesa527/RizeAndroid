package com.rize.rizeandroid.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

@Entity(
    tableName = "rep_squat_details",
    foreignKeys = [
        ForeignKey(
            entity = SessionRep::class,
            parentColumns = ["id"],
            childColumns = ["rep_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class RepSquatDetails(
    @PrimaryKey
    @ColumnInfo(name = "rep_id")
    val repId: Long,

    @ColumnInfo(name = "min_knee_angle_deg")
    val minKneeAngleDeg: Double?,

    @ColumnInfo(name = "min_hip_angle_deg")
    val minHipAngleDeg: Double?,

    @ColumnInfo(name = "depth_insufficient")
    val depthInsufficient: Boolean,

    @ColumnInfo(name = "trunk_lean_risk")
    val trunkLeanRisk: Boolean,

    @ColumnInfo(name = "eccentric_peak_velocity_deg_s")
    val eccentricPeakVelocityDegS: Double?
)
