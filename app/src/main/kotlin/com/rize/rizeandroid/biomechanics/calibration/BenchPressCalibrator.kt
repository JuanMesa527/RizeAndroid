package com.rize.rizeandroid.biomechanics.calibration

import android.util.Log

/**
 * Maneja la calibracion de umbrales para bench press a partir de muestras tomadas
 * durante la fase READY (pose canonica con codos extendidos). Mantiene un estado
 * interno que evoluciona segun las muestras recibidas y expone los umbrales
 * calibrados una vez comprometidos.
 * 
 * @param enabled Si false, el calibrador se inicia en estado DISABLED y no procesa muestras
 * hasta que se resetee con enabled=true. Esto permite controlar desde afuera si se quiere usar calibracion o no, sin necesidad de crear o destruir el objeto.
 */
class BenchPressCalibrator(enabled: Boolean = true) {

    private var enabled: Boolean = enabled

    enum class State { WAITING, COLLECTING, COMMITTED, DISABLED }

    var state: State = if (enabled) State.WAITING else State.DISABLED
        private set

    var thresholds: BenchPressCalibratedThresholds = BenchPressCalibratedThresholds.canonical()
        private set

    var profile: BenchPressEnvironmentProfile? = null
        private set

    private val collected = ArrayDeque<BenchPressEnvironmentProfile>()
    private var rejectedThisSession = false
    private var framesSinceLastLog = 0

    /**
     * Alimenta el calibrador con un frame cuando el algoritmo esta en READY.
     * Requiere [currentElbowAngleDeg] para verificar que la pose este en
     * lockout (la calibracion solo es valida en la pose canonica).
     */
    fun onReadyFrame(flat: List<Double>, currentElbowAngleDeg: Double) {
        if (state == State.COMMITTED || state == State.DISABLED) return
        if (rejectedThisSession) {
            logPeriodic("REJECTED this session, using canonical. Reset exercise to retry.")
            return
        }
       
        if (currentElbowAngleDeg < LOCKOUT_ELBOW_GATE_DEG) {
            if (collected.isNotEmpty()) collected.clear()
            state = State.COLLECTING
            logPeriodic("READY but elbow=%.0f < %.0f (lockout gate) — extend arms fully".format(
                currentElbowAngleDeg, LOCKOUT_ELBOW_GATE_DEG))
            return
        }

        val sample = BenchPressEnvironmentProfile.fromLandmarks(flat)
        if (sample == null) {
            logPeriodic("READY, elbow=%.0f OK, but arm landmarks not visible — check framing".format(
                currentElbowAngleDeg))
            return
        }

        if (collected.size >= COLLECT_WINDOW) collected.removeFirst()
        collected.addLast(sample)
        state = State.COLLECTING

        logPeriodic("COLLECTING %d/%d | elbow=%.0f torsoTilt=%.1f grip2D=%.2f".format(
            collected.size, COLLECT_WINDOW,
            currentElbowAngleDeg, sample.torsoTiltDegFromVertical, sample.grip2DRatioAtRest))

        if (collected.size >= COLLECT_WINDOW) {
            val merged = BenchPressEnvironmentProfile.medianOf(collected.toList())
            if (BenchPressCalibratedThresholds.isProfileSane(merged)) {
                profile = merged
                thresholds = BenchPressCalibratedThresholds.from(merged)
                state = State.COMMITTED
                Log.i(TAG, "COMMITTED | torsoTilt=%.1f° grip2D=%.2f shoulderW=%.3f | " .format(
                    merged.torsoTiltDegFromVertical, merged.grip2DRatioAtRest, merged.shoulderWidthNormalized) +
                    "topAngle=%.1f° maxBottom=%.1f° gripCorr=%.3f sticking=%.1fdeg/s".format(
                        thresholds.topPositionAngleDeg, thresholds.maxValidBottomAngleDeg,
                        thresholds.gripPerspectiveCorrection, thresholds.stickingVelocityThresholdDegS))
            } else {
                Log.w(TAG, "SANITY REJECTED | torsoTilt=%.1f° grip2D=%.2f shoulderW=%.3f armRatio=%.2f".format(
                    merged.torsoTiltDegFromVertical, merged.grip2DRatioAtRest,
                    merged.shoulderWidthNormalized, merged.upperArmToTorsoLengthRatio) +
                    " — check: tilt<60, shoulderW>0.04, grip in [0.8,4.0]")
                rejectedThisSession = true
                collected.clear()
                state = State.WAITING
            }
        }
    }

    private fun logPeriodic(msg: String) {
        framesSinceLastLog++
        if (framesSinceLastLog >= LOG_INTERVAL_FRAMES) {
            framesSinceLastLog = 0
            Log.d(TAG, msg)
        }
    }

    /**
     * El algoritmo perdio el estado READY (perdida de pose o degradacion).
     * Si estabamos COLLECTING, descartamos la ventana parcial. 
     */
    fun onReadinessLost() {
        if (state == State.COLLECTING) {
            Log.d(TAG, "READY lost while COLLECTING — discarding ${collected.size} samples, back to WAITING")
            collected.clear()
            state = State.WAITING
        }
    }

    fun reset(enabled: Boolean = this.enabled) {
        this.enabled = enabled
        collected.clear()
        thresholds = BenchPressCalibratedThresholds.canonical()
        profile = null
        rejectedThisSession = false
        framesSinceLastLog = 0
        state = if (enabled) State.WAITING else State.DISABLED
        Log.d(TAG, "reset — state=${state}, calibrationEnabled=$enabled")
    }

    companion object {
        private const val TAG = "BenchCalib"
     
        const val COLLECT_WINDOW = 15
      
        const val LOCKOUT_ELBOW_GATE_DEG = 150.0
        
        private const val LOG_INTERVAL_FRAMES = 30
    }
}
