package com.rize.rizeandroid.data

import com.rize.rizeandroid.AlgorithmResult
import com.rize.rizeandroid.ErrorLevel
import com.rize.rizeandroid.data.entity.BenchSessionDetails
import com.rize.rizeandroid.data.entity.CurlSessionDetails
import com.rize.rizeandroid.data.entity.RepBenchDetails
import com.rize.rizeandroid.data.entity.RepCurlDetails
import com.rize.rizeandroid.data.entity.RepSquatDetails
import com.rize.rizeandroid.data.entity.SessionRep
import com.rize.rizeandroid.data.entity.SquatSessionDetails
import com.rize.rizeandroid.data.entity.WorkoutSession

/**
 * Construye un [PendingSessionData] listo para insertar en la DB a partir del
 * estado del algoritmo al cierre de sesión y la lista de snapshots por rep
 * acumulados durante la captura.
 *
 * Llamado desde Java vía [PendingSessionBuilder.build].
 */
object PendingSessionBuilder {

    private data class ErrorMetric(
        val label: String,
        val count: Int,
        val total: Int,
        val percent: Int = if (total > 0) ((count * 100.0) / total).toInt() else 0
    )

    @JvmStatic
    fun build(
        exerciseType: String,
        exerciseName: String,
        sessionStartMs: Long,
        sessionEndMs: Long,
        durationSeconds: Int,
        autoSaved: Boolean,
        finalResult: AlgorithmResult?,
        reps: List<PendingRep>
    ): PendingSessionData {
        val commonErrorLabel = resolveCommonErrorLabel(exerciseType, reps, finalResult)
        val session = WorkoutSession(
            exerciseType = exerciseType,
            exerciseName = exerciseName,
            startedAt = sessionStartMs,
            endedAt = sessionEndMs,
            durationSeconds = durationSeconds,
            totalReps = reps.size,
            autoSaved = autoSaved,
            concentricPeakVelocityDegS = finalResult?.concentricPeakVelocityDegS,
            velocityLossPercent = finalResult?.velocityLossPercent,
            technicalErrorLevel = commonErrorLabel,
            avgErrorMagnitude = null
        )

        return when (exerciseType) {
            WorkoutSession.TYPE_SQUAT -> PendingSessionData(
                session = session,
                squatDetails = buildSquatSessionDetails(finalResult, reps),
                reps = reps
            )
            WorkoutSession.TYPE_CURL -> PendingSessionData(
                session = session,
                curlDetails = buildCurlSessionDetails(reps),
                reps = reps
            )
            WorkoutSession.TYPE_BENCH -> PendingSessionData(
                session = session,
                benchDetails = buildBenchSessionDetails(reps),
                reps = reps
            )
            else -> PendingSessionData(session = session, reps = reps)
        }
    }

    private fun resolveCommonErrorLabel(
        exerciseType: String,
        reps: List<PendingRep>,
        finalResult: AlgorithmResult?
    ): String {
        if (reps.isEmpty()) return "Sin repeticiones"

        val metric = when (exerciseType) {
            WorkoutSession.TYPE_SQUAT -> resolveCommonSquatError(reps)
            WorkoutSession.TYPE_BENCH -> resolveCommonBenchError(reps)
            WorkoutSession.TYPE_CURL -> resolveCommonCurlError(reps)
            else -> null
        }

        if (metric != null && metric.count > 0) {
            return "${metric.label} (${metric.count} reps, ${metric.percent}%)"
        }

        return if ((finalResult?.technicalError ?: ErrorLevel.NONE) == ErrorLevel.NONE) {
            "Sin error tecnico dominante"
        } else {
            "Error tecnico sin clasificacion"
        }
    }

    private fun resolveCommonSquatError(reps: List<PendingRep>): ErrorMetric? {
        val squatReps = reps.mapNotNull { it.squatDetails }
        if (squatReps.isEmpty()) return null
        val total = squatReps.size

        val candidates = listOf(
            ErrorMetric("Profundidad insuficiente", squatReps.count { it.depthInsufficient }, total),
            ErrorMetric("Tronco muy inclinado", squatReps.count { it.trunkLeanRisk }, total)
        )
        return candidates.maxByOrNull { it.count }
    }

    private fun resolveCommonBenchError(reps: List<PendingRep>): ErrorMetric? {
        val benchReps = reps.mapNotNull { it.benchDetails }
        if (benchReps.isEmpty()) return null
        val total = benchReps.size

        val candidates = listOf(
            ErrorMetric("Profundidad insuficiente", benchReps.count { it.depthInsufficient }, total),
            ErrorMetric("Extension incompleta", benchReps.count { it.extensionIncomplete }, total),
            ErrorMetric("Agarre demasiado ancho", benchReps.count { it.gripTooWide }, total),
            ErrorMetric("Asimetria bilateral", benchReps.count { it.bilateralAsymmetry }, total),
            ErrorMetric("Periodo de estancamiento", benchReps.count { it.stickingPeriodDetected }, total)
        )
        return candidates.maxByOrNull { it.count }
    }

    private fun resolveCommonCurlError(reps: List<PendingRep>): ErrorMetric? {
        val total = reps.size
        if (total == 0) return null

        val lowRomCount = reps.count { (it.rep.romDeg ?: Double.MAX_VALUE) < 110.0 }
        val shoulderCompCount = reps
            .mapNotNull { it.curlDetails?.shoulderCompensationDeg }
            .count { it > 15.0 }

        val candidates = listOf(
            ErrorMetric("Rango de movimiento incompleto", lowRomCount, total),
            ErrorMetric("Compensacion de hombro", shoulderCompCount, total)
        )
        return candidates.maxByOrNull { it.count }
    }

    /**
     * Crea un PendingRep para Squat a partir del AlgorithmResult emitido tras
     * cerrar la rep. session_id y rep_id se cablean en el repository.
     */
    @JvmStatic
    fun buildSquatRep(
        repNumber: Int,
        timestampOffsetMs: Long,
        result: AlgorithmResult
    ): PendingRep {
        val rep = SessionRep(
            sessionId = 0,
            repNumber = repNumber,
            timestampOffsetMs = timestampOffsetMs,
            peakAngleDeg = result.lastRepMinKneeAngleDeg,
            valleyAngleDeg = null, // squat: la "valley" es el angulo inicial (top), no se rastrea por rep
            romDeg = result.lastRepSquatRomDeg,
            peakVelocityDegS = result.lastRepConcentricPeakVelocityDegS,
            formQuality = (result.lastRepFormQuality ?: ErrorLevel.NONE).name
        )
        val details = RepSquatDetails(
            repId = 0,
            minKneeAngleDeg = result.lastRepMinKneeAngleDeg,
            minHipAngleDeg = result.lastRepMinHipAngleDeg,
            depthInsufficient = result.lastRepDepthInsufficient,
            trunkLeanRisk = result.lastRepTrunkLeanRisk,
            eccentricPeakVelocityDegS = result.lastRepEccentricPeakVelocityDegS
        )
        return PendingRep(rep = rep, squatDetails = details)
    }

    @JvmStatic
    fun buildCurlRep(
        repNumber: Int,
        timestampOffsetMs: Long,
        result: AlgorithmResult
    ): PendingRep {
        val peak = result.lastRepPeakFlexionDeg
        val rom = result.lastRepRomDeg
        val valley = if (peak != null && rom != null) peak + rom else null
        val rep = SessionRep(
            sessionId = 0,
            repNumber = repNumber,
            timestampOffsetMs = timestampOffsetMs,
            peakAngleDeg = peak,
            valleyAngleDeg = valley,
            romDeg = rom,
            peakVelocityDegS = result.lastRepConcentricPeakVelocityDegS,
            formQuality = (result.lastRepFormQuality ?: ErrorLevel.NONE).name
        )
        val details = RepCurlDetails(
            repId = 0,
            peakFlexionDeg = peak,
            shoulderCompensationDeg = result.lastRepShoulderCompensationDeg
        )
        return PendingRep(rep = rep, curlDetails = details)
    }

    @JvmStatic
    fun buildBenchRep(
        repNumber: Int,
        timestampOffsetMs: Long,
        result: AlgorithmResult
    ): PendingRep {
        val rep = SessionRep(
            sessionId = 0,
            repNumber = repNumber,
            timestampOffsetMs = timestampOffsetMs,
            peakAngleDeg = result.lastRepMinElbowAngleDeg,
            valleyAngleDeg = null, // bench: la "valley" es el lockout (top); no se rastrea por rep
            romDeg = result.lastRepBenchRomDeg,
            peakVelocityDegS = result.lastRepConcentricPeakVelocityDegS,
            formQuality = (result.lastRepFormQuality ?: ErrorLevel.NONE).name
        )
        val details = RepBenchDetails(
            repId = 0,
            minElbowAngleDeg = result.lastRepMinElbowAngleDeg,
            bilateralAsymmetryDeg = result.lastRepBilateralAsymmetryDeg,
            shoulderAbductionDeg = result.lastRepShoulderAbductionDeg,
            extensionIncompleteDeg = result.lastRepExtensionIncompleteDeg,
            gripWidthRatio = result.lastRepGripWidthRatio,
            stickingPeriodDetected = result.lastRepStickingPeriodDetected,
            gripTooWide = result.lastRepGripTooWide,
            bilateralAsymmetry = result.lastRepBilateralAsymmetry,
            depthInsufficient = result.lastRepDepthInsufficientBench,
            extensionIncomplete = result.lastRepExtensionIncomplete
        )
        return PendingRep(rep = rep, benchDetails = details)
    }

    // ── Aggregates ───────────────────────────────────────────────────────────

    private fun buildSquatSessionDetails(
        finalResult: AlgorithmResult?,
        reps: List<PendingRep>
    ): SquatSessionDetails {
        val squatReps = reps.mapNotNull { it.squatDetails }
        return SquatSessionDetails(
            sessionId = 0,
            cvtPercent = finalResult?.cvtPercent,
            eccentricPeakVelocityDegS = squatReps.mapNotNull { it.eccentricPeakVelocityDegS }.maxOrNull(),
            minKneeAngleDeg = squatReps.mapNotNull { it.minKneeAngleDeg }.minOrNull(),
            minHipAngleDeg = squatReps.mapNotNull { it.minHipAngleDeg }.minOrNull(),
            depthInsufficientCount = squatReps.count { it.depthInsufficient },
            trunkLeanRiskCount = squatReps.count { it.trunkLeanRisk }
        )
    }

    private fun buildCurlSessionDetails(reps: List<PendingRep>): CurlSessionDetails {
        val curlReps = reps.mapNotNull { it.curlDetails }
        val romValues = reps.mapNotNull { it.rep.romDeg }
        return CurlSessionDetails(
            sessionId = 0,
            avgPeakFlexionDeg = avgOfNotNull(curlReps.map { it.peakFlexionDeg }),
            avgRomDeg = if (romValues.isEmpty()) null else romValues.average(),
            maxShoulderCompensationDeg = curlReps.mapNotNull { it.shoulderCompensationDeg }.maxOrNull(),
            avgShoulderCompensationDeg = avgOfNotNull(curlReps.map { it.shoulderCompensationDeg })
        )
    }

    private fun buildBenchSessionDetails(reps: List<PendingRep>): BenchSessionDetails {
        val benchReps = reps.mapNotNull { it.benchDetails }
        return BenchSessionDetails(
            sessionId = 0,
            avgGripWidthRatio = avgOfNotNull(benchReps.map { it.gripWidthRatio }),
            avgShoulderAbductionDeg = avgOfNotNull(benchReps.map { it.shoulderAbductionDeg }),
            avgBilateralAsymmetryDeg = avgOfNotNull(benchReps.map { it.bilateralAsymmetryDeg }),
            extensionIncompleteCount = benchReps.count { it.extensionIncomplete },
            stickingPeriodCount = benchReps.count { it.stickingPeriodDetected },
            gripTooWideCount = benchReps.count { it.gripTooWide },
            bilateralAsymmetryCount = benchReps.count { it.bilateralAsymmetry },
            depthInsufficientCount = benchReps.count { it.depthInsufficient }
        )
    }

    private fun avgOfNotNull(values: List<Double?>): Double? {
        val xs = values.filterNotNull()
        return if (xs.isEmpty()) null else xs.average()
    }
}
