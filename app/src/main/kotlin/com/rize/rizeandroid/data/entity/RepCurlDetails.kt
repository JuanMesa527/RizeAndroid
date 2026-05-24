package com.rize.rizeandroid.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/**
 * Entidad que representa los detalles específicos de una repeticion en un curl de bíceps.
 */
@Entity(
    tableName = "rep_curl_details",
    foreignKeys = [
        ForeignKey(
            entity = SessionRep::class,
            parentColumns = ["id"],
            childColumns = ["rep_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class RepCurlDetails(
    @PrimaryKey
    @ColumnInfo(name = "rep_id")
    val repId: Long,

    @ColumnInfo(name = "peak_flexion_deg")
    val peakFlexionDeg: Double?,

    @ColumnInfo(name = "shoulder_compensation_deg")
    val shoulderCompensationDeg: Double?
)
