package com.rize.rizeandroid.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.rize.rizeandroid.data.entity.BenchSessionDetails
import com.rize.rizeandroid.data.entity.CurlSessionDetails
import com.rize.rizeandroid.data.entity.SquatSessionDetails
import com.rize.rizeandroid.data.entity.WorkoutSession

@Dao
interface WorkoutSessionDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(session: WorkoutSession): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertSquatDetails(details: SquatSessionDetails)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertCurlDetails(details: CurlSessionDetails)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertBenchDetails(details: BenchSessionDetails)

    @Query("SELECT * FROM workout_session ORDER BY started_at DESC")
    fun getAll(): List<WorkoutSession>

    @Query("SELECT * FROM workout_session WHERE exercise_type = :type ORDER BY started_at DESC")
    fun getByExerciseType(type: String): List<WorkoutSession>

    @Query("SELECT * FROM workout_session WHERE id = :sessionId")
    fun getById(sessionId: Long): WorkoutSession?

    @Query("SELECT * FROM squat_session_details WHERE session_id = :sessionId")
    fun getSquatDetailsBySessionId(sessionId: Long): SquatSessionDetails?

    @Query("SELECT * FROM curl_session_details WHERE session_id = :sessionId")
    fun getCurlDetailsBySessionId(sessionId: Long): CurlSessionDetails?

    @Query("SELECT * FROM bench_session_details WHERE session_id = :sessionId")
    fun getBenchDetailsBySessionId(sessionId: Long): BenchSessionDetails?

    @Query("DELETE FROM workout_session WHERE id = :sessionId")
    fun deleteById(sessionId: Long)

    @Query("SELECT COUNT(*) FROM workout_session")
    fun count(): Int

    @Query("SELECT COUNT(*) FROM workout_session WHERE exercise_type = :type")
    fun countByType(type: String): Int

    @Query("SELECT AVG(total_reps) FROM workout_session WHERE exercise_type = :type")
    fun avgRepsByType(type: String): Double?

    @Query("SELECT AVG(duration_seconds) FROM workout_session WHERE exercise_type = :type")
    fun avgDurationByType(type: String): Double?
}
