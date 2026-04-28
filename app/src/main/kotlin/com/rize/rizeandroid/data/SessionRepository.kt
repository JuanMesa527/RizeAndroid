package com.rize.rizeandroid.data

import com.rize.rizeandroid.data.entity.WorkoutSession
import com.rize.rizeandroid.data.entity.BenchSessionDetails
import com.rize.rizeandroid.data.entity.CurlSessionDetails
import com.rize.rizeandroid.data.entity.SquatSessionDetails
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * API para Activities (Java-friendly). Esconde los DAOs y aplica una única
 * transacción por sesión guardada (sesión + details + N reps + N rep_details).
 *
 * Las llamadas pesadas se ejecutan en un single-thread executor — no usar el
 * hilo principal.
 */
class SessionRepository(private val database: RizeDatabase) {

    private val sessionDao = database.workoutSessionDao()
    private val repDao = database.repDao()
    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    /**
     * Versión bloqueante (para llamar desde un hilo de background ya creado).
     * Devuelve el id autogenerado de la sesión.
     */
    fun saveSessionBlocking(data: PendingSessionData): Long {
        var sessionId: Long = -1
        database.runInTransaction {
            sessionId = sessionDao.insert(data.session)
            data.squatDetails?.let {
                sessionDao.insertSquatDetails(it.copy(sessionId = sessionId))
            }
            data.curlDetails?.let {
                sessionDao.insertCurlDetails(it.copy(sessionId = sessionId))
            }
            data.benchDetails?.let {
                sessionDao.insertBenchDetails(it.copy(sessionId = sessionId))
            }
            for (pendingRep in data.reps) {
                val repId = repDao.insertRep(pendingRep.rep.copy(sessionId = sessionId))
                pendingRep.squatDetails?.let {
                    repDao.insertSquatDetails(it.copy(repId = repId))
                }
                pendingRep.curlDetails?.let {
                    repDao.insertCurlDetails(it.copy(repId = repId))
                }
                pendingRep.benchDetails?.let {
                    repDao.insertBenchDetails(it.copy(repId = repId))
                }
            }
        }
        return sessionId
    }

    /**
     * Versión asíncrona — invoca [onResult] en el mismo hilo de background al
     * terminar. Útil cuando el caller no quiere gestionar threads.
     */
    fun saveSessionAsync(data: PendingSessionData, onResult: ((Long) -> Unit)?) {
        ioExecutor.execute {
            val id = try {
                saveSessionBlocking(data)
            } catch (t: Throwable) {
                -1L
            }
            onResult?.invoke(id)
        }
    }

    /** Java-friendly overload: dispara la insercion sin callback. */
    fun saveSessionAsync(data: PendingSessionData) {
        saveSessionAsync(data, null)
    }

    fun getAllSessionsBlocking(): List<WorkoutSession> = sessionDao.getAll()

    fun getSessionsByTypeBlocking(type: String): List<WorkoutSession> =
        sessionDao.getByExerciseType(type)

    fun getSquatDetailsBlocking(sessionId: Long): SquatSessionDetails? =
        sessionDao.getSquatDetailsBySessionId(sessionId)

    fun getCurlDetailsBlocking(sessionId: Long): CurlSessionDetails? =
        sessionDao.getCurlDetailsBySessionId(sessionId)

    fun getBenchDetailsBlocking(sessionId: Long): BenchSessionDetails? =
        sessionDao.getBenchDetailsBySessionId(sessionId)

    // ── Estadísticas Agregadas ───────────────────────────────────────────────────

    data class ExerciseStats(
        val type: String,
        val displayName: String,
        val sessionCount: Int,
        val avgReps: Double?,
        val avgDurationSec: Double?
    )

    fun getExerciseStatsBlocking(): List<ExerciseStats> {
        val types = listOf(
            "squat" to "Sentadilla",
            "curl" to "Curl",
            "bench" to "Banca"
        )
        return types.map { (type, displayName) ->
            ExerciseStats(
                type = type,
                displayName = displayName,
                sessionCount = sessionDao.countByType(type),
                avgReps = sessionDao.avgRepsByType(type),
                avgDurationSec = sessionDao.avgDurationByType(type)
            )
        }.filter { it.sessionCount > 0 }
    }

    fun getLocalSummaryBlocking(): LocalSummary {
        val allSessions = getAllSessionsBlocking()
        return LocalSummary(
            totalSessions = allSessions.size,
            totalReps = allSessions.sumOf { it.totalReps },
            sessionsByType = mapOf(
                "squat" to sessionDao.countByType("squat"),
                "curl" to sessionDao.countByType("curl"),
                "bench" to sessionDao.countByType("bench")
            )
        )
    }

    data class LocalSummary(
        val totalSessions: Int,
        val totalReps: Int,
        val sessionsByType: Map<String, Int>
    )
}
