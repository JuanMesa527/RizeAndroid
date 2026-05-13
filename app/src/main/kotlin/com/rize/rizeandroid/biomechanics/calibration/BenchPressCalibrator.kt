package com.rize.rizeandroid.biomechanics.calibration

import android.util.Log

/**
 * Maquina de estados que captura el [BenchPressEnvironmentProfile] durante
 * la ventana READY al inicio de una sesion y produce los
 * [BenchPressCalibratedThresholds] resultantes.
 *
 * Flujo:
 *   1. WAITING (o DISABLED si el flag global esta apagado).
 *   2. Cuando el algoritmo entra en READY y el codo esta cerca de lockout
 *      (>= [LOCKOUT_ELBOW_GATE_DEG]) -> COLLECTING. Empiezan a acumularse
 *      muestras del perfil.
 *   3. Cuando se acumulan [COLLECT_WINDOW] muestras consecutivas, se calcula
 *      el perfil mediana y, si pasa [BenchPressCalibratedThresholds.isProfileSane],
 *      se commitea -> COMMITTED. A partir de aqui los umbrales no cambian.
 *   4. Si el perfil falla la sanidad, se descarta y se vuelve a WAITING
 *      (sin reintentar inmediatamente para no spamear).
 *
 * Una vez COMMITTED, mover la camara o perder READY NO recalibra: los
 * umbrales se mantienen estables durante toda la sesion. Solo [reset]
 * (cambio de ejercicio o sesion nueva) limpia el estado.
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

        // Gate: exigir codo extendido (cerca de lockout).
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
     * Si estabamos COLLECTING, descartamos la ventana parcial. Si ya estamos
     * COMMITTED, no se toca: los umbrales calibrados se mantienen para el
     * resto de la sesion aunque la cobertura sea intermitente.
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

        // 15 frames @ 30 Hz = 0.5 s de muestras estables.
        const val COLLECT_WINDOW = 15

        // Codo extendido para considerar la pose como lockout canonico.
        const val LOCKOUT_ELBOW_GATE_DEG = 150.0

        // Log periodico cada N frames para no saturar logcat (1 log ~ cada 1 s a 30 Hz).
        private const val LOG_INTERVAL_FRAMES = 30
    }
}
