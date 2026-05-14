package com.rize.rizeandroid.data

import com.rize.rizeandroid.data.entity.WorkoutSession
import com.rize.rizeandroid.data.entity.BenchSessionDetails
import com.rize.rizeandroid.data.entity.CurlSessionDetails
import com.rize.rizeandroid.data.entity.SquatSessionDetails
import com.rize.rizeandroid.data.entity.SessionRep
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

    /**
     * Intentos registrados en la sesión (desde detalles o, si no hay dato, número de reps válidas).
     * Misma lógica que al reconstruir el resumen.
     */
    fun getAttemptedRepCountBlocking(session: WorkoutSession): Int {
        val id = session.id
        val type = session.exerciseType
        val validCount = repDao.getRepsForSession(id).size
        val squat = if (type == WorkoutSession.TYPE_SQUAT) sessionDao.getSquatDetailsBySessionId(id) else null
        val curl = if (type == WorkoutSession.TYPE_CURL) sessionDao.getCurlDetailsBySessionId(id) else null
        val bench = if (type == WorkoutSession.TYPE_BENCH) sessionDao.getBenchDetailsBySessionId(id) else null
        return attemptedRepCountFromDetails(type, squat, curl, bench, validCount)
    }

    /**
     * Reconstruye [PendingSessionData] desde la base (sesión + detalles + reps)
     * para mostrar un resumen idéntico al de [com.rize.rizeandroid.SummaryActivity].
     * Llamar solo desde un hilo de fondo.
     */
    fun loadPendingSessionDataBlocking(sessionId: Long): PendingSessionData? {
        val session = sessionDao.getById(sessionId) ?: return null
        val type = session.exerciseType

        val squatDetails =
            if (type == WorkoutSession.TYPE_SQUAT) sessionDao.getSquatDetailsBySessionId(sessionId)
            else null
        val curlDetails =
            if (type == WorkoutSession.TYPE_CURL) sessionDao.getCurlDetailsBySessionId(sessionId)
            else null
        val benchDetails =
            if (type == WorkoutSession.TYPE_BENCH) sessionDao.getBenchDetailsBySessionId(sessionId)
            else null

        val reps = repDao.getRepsForSession(sessionId).map { rep ->
            mapRepToPending(rep, type)
        }

        val attempted = attemptedRepCountFromDetails(type, squatDetails, curlDetails, benchDetails, reps.size)

        return PendingSessionData(
            session = session,
            squatDetails = squatDetails,
            curlDetails = curlDetails,
            benchDetails = benchDetails,
            reps = reps,
            attemptedRepCount = attempted
        )
    }

    private fun mapRepToPending(rep: SessionRep, exerciseType: String): PendingRep {
        return when (exerciseType) {
            WorkoutSession.TYPE_SQUAT -> {
                val d = repDao.getSquatDetailsByRepId(rep.id)
                PendingRep(rep = rep, squatDetails = d)
            }
            WorkoutSession.TYPE_CURL -> {
                val d = repDao.getCurlDetailsByRepId(rep.id)
                PendingRep(rep = rep, curlDetails = d)
            }
            WorkoutSession.TYPE_BENCH -> {
                val d = repDao.getBenchDetailsByRepId(rep.id)
                PendingRep(rep = rep, benchDetails = d)
            }
            else -> PendingRep(rep = rep)
        }
    }

    /**
     * Igual que el fallback del builder: si no hay contador persistido (0 en sesiones viejas),
     * usar número de reps válidas guardadas.
     */
    private fun attemptedRepCountFromDetails(
        type: String,
        squat: SquatSessionDetails?,
        curl: CurlSessionDetails?,
        bench: BenchSessionDetails?,
        validRepCount: Int
    ): Int {
        val stored = when (type) {
            WorkoutSession.TYPE_SQUAT -> squat?.attemptedRepCount
            WorkoutSession.TYPE_CURL -> curl?.attemptedRepCount
            WorkoutSession.TYPE_BENCH -> bench?.attemptedRepCount
            else -> null
        }
        return if (stored != null && stored > 0) stored else validRepCount
    }

    // ── Estadísticas Agregadas ───────────────────────────────────────────────────

    data class CurlRepQualityCounts(val good: Int, val almost: Int, val bad: Int)

    fun getCurlRepQualityCountsBlocking(sessionId: Long): CurlRepQualityCounts {
        val reps = repDao.getRepsForSession(sessionId)
        var good = 0
        var almost = 0
        for (rep in reps) {
            when (rep.formQuality) {
                "NONE" -> good++
                "MILD" -> almost++
            }
        }
        val curl = sessionDao.getCurlDetailsBySessionId(sessionId)
        val attempted = if (curl != null && curl.attemptedRepCount > 0) curl.attemptedRepCount else reps.size
        val bad = maxOf(0, attempted - good - almost)
        return CurlRepQualityCounts(good, almost, bad)
    }

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
        }
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
