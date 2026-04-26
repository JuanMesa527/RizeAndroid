package com.rize.rizeandroid.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.rize.rizeandroid.data.entity.RepBenchDetails
import com.rize.rizeandroid.data.entity.RepCurlDetails
import com.rize.rizeandroid.data.entity.RepSquatDetails
import com.rize.rizeandroid.data.entity.SessionRep

@Dao
interface RepDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertRep(rep: SessionRep): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertSquatDetails(details: RepSquatDetails)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertCurlDetails(details: RepCurlDetails)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertBenchDetails(details: RepBenchDetails)

    @Query("SELECT * FROM session_rep WHERE session_id = :sessionId ORDER BY rep_number ASC")
    fun getRepsForSession(sessionId: Long): List<SessionRep>
}
