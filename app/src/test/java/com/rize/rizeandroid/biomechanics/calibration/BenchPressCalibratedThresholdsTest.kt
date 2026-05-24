package com.rize.rizeandroid.biomechanics.calibration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.tan

class BenchPressCalibratedThresholdsTest {

    private val tol = 1e-3

    // ── Canonical ───────────────────────────────────────────────────────────

    @Test
    fun canonicalMatchesHistoricalHardcodedValues() {
        val c = BenchPressCalibratedThresholds.canonical()
        // Estos eran los valores hardcoded del companion object antes de la
        // refactorizacion. La canonica DEBE igualarlos exactamente para no
        // regresionar en el setup de casa donde theta ~ 0.
        assertEquals(0.6, c.gripPerspectiveCorrection, tol)
        assertEquals(10.0, c.stickingVelocityThresholdDegS, tol)
        assertEquals(870L, c.stickingDurationMs)
        assertEquals(60.0, c.minValidBottomAngleDeg, tol)
        assertEquals(85.0, c.maxValidBottomAngleDeg, tol)
        assertEquals(160.0, c.fullExtensionMinDeg, tol)
        assertEquals(160.0, c.topPositionAngleDeg, tol)
        assertEquals(155.0, c.startDescentAngleDeg, tol)
        assertEquals(45.0, c.abductionMinOkDeg, tol)
        assertEquals(85.0, c.abductionMaxOkDeg, tol)
        assertEquals(80.0, c.abductionDeepElbowDeg, tol)
        assertEquals(55.0, c.abductionDeepMaxOkDeg, tol)
        assertEquals(90.0, c.abductionCriticalDeg, tol)
        assertEquals(90.0, c.abductionEvaluationElbowDeg, tol)
    }

    // ── Projection geometry ─────────────────────────────────────────────────

    @Test
    fun projectionAtZeroDegreesReturnsBetaUnchanged() {
        // Sin inclinacion del banco, beta_2D = beta. Este es el invariante
        // que garantiza cero cambio de comportamiento en setup canonico.
        for (beta in listOf(60.0, 85.0, 90.0, 155.0, 160.0)) {
            assertEquals(
                "project($beta, 0) should return $beta",
                beta,
                BenchPressCalibratedThresholds.project(beta, 0.0),
                tol
            )
        }
    }

    @Test
    fun projectionDecreasesMonotonicallyWithTheta() {
        // Para beta < 180, beta_2D debe disminuir conforme aumenta theta.
        val beta = 90.0
        val p0 = BenchPressCalibratedThresholds.project(beta, 0.0)
        val p15 = BenchPressCalibratedThresholds.project(beta, 15.0)
        val p30 = BenchPressCalibratedThresholds.project(beta, 30.0)
        val p45 = BenchPressCalibratedThresholds.project(beta, 45.0)
        assertTrue(p0 > p15)
        assertTrue(p15 > p30)
        assertTrue(p30 > p45)
    }

    @Test
    fun projectionMatchesAnalyticalFormulaAtKnownPoints() {
        // beta_2D = 2 * atan(tan(beta/2) * cos(theta))
        fun expected(beta: Double, theta: Double): Double {
            val cosT = cos(Math.toRadians(theta))
            return 2.0 * Math.toDegrees(atan(tan(Math.toRadians(beta / 2.0)) * cosT))
        }
        for (theta in listOf(0.0, 10.0, 20.0, 30.0, 45.0)) {
            for (beta in listOf(60.0, 90.0, 155.0, 160.0)) {
                assertEquals(
                    expected(beta, theta),
                    BenchPressCalibratedThresholds.project(beta, theta),
                    tol
                )
            }
        }
    }

    @Test
    fun projectionAt30DegreesMatchesAnalyticalValues() {
        // Valores recalculados con la formula exacta:
        //   beta_2D = 2 * atan(tan(beta/2) * cos(30 deg))
        assertEquals(157.0, BenchPressCalibratedThresholds.project(160.0, 30.0), 0.5)
        assertEquals(76.87, BenchPressCalibratedThresholds.project(85.0, 30.0), 0.1)
        assertEquals(53.13, BenchPressCalibratedThresholds.project(60.0, 30.0), 0.1)
    }

    // ── from(profile) ───────────────────────────────────────────────────────

    @Test
    fun fromProfileWithZeroTiltApproximatesCanonical() {
        // Cuando theta = 0 y grip 2D = 2.5 (la calibracion empirica historica),
        // grip correction = 1.5/2.5 = 0.6 = canonical. Los demas umbrales
        // deben coincidir con canonical dentro de 0.1 grados.
        val profile = BenchPressEnvironmentProfile(
            torsoTiltDegFromVertical = 0.0,
            shoulderWidthNormalized = 0.2,
            grip2DRatioAtRest = 2.5,
            upperArmToTorsoLengthRatio = 1.0,
            cameraYawHintDeg = 0.0
        )
        val calibrated = BenchPressCalibratedThresholds.from(profile)
        val canonical = BenchPressCalibratedThresholds.canonical()
        assertEquals(canonical.gripPerspectiveCorrection, calibrated.gripPerspectiveCorrection, tol)
        assertEquals(canonical.topPositionAngleDeg, calibrated.topPositionAngleDeg, 0.1)
        assertEquals(canonical.minValidBottomAngleDeg, calibrated.minValidBottomAngleDeg, 0.1)
        assertEquals(canonical.maxValidBottomAngleDeg, calibrated.maxValidBottomAngleDeg, 0.1)
        assertEquals(canonical.startDescentAngleDeg, calibrated.startDescentAngleDeg, 0.1)
        assertEquals(canonical.stickingVelocityThresholdDegS, calibrated.stickingVelocityThresholdDegS, 0.1)
        assertEquals(canonical.fullExtensionMinDeg, calibrated.fullExtensionMinDeg, 0.1)
    }

    @Test
    fun fromProfileWithInclinedBenchTightensElbowThresholds() {
        // theta = 30 grados (banco inclinado) -> umbrales de codo proyectados
        // mas bajos (rep cuenta "cerrada" a menos angulo visible).
        val profile = BenchPressEnvironmentProfile(
            torsoTiltDegFromVertical = 30.0,
            shoulderWidthNormalized = 0.2,
            grip2DRatioAtRest = 2.5,
            upperArmToTorsoLengthRatio = 1.0,
            cameraYawHintDeg = 0.0
        )
        val c = BenchPressCalibratedThresholds.from(profile)
        assertTrue("topPositionAngleDeg debe bajar con theta", c.topPositionAngleDeg < 160.0)
        assertTrue("maxValidBottomAngleDeg debe bajar con theta", c.maxValidBottomAngleDeg < 85.0)
        assertTrue("minValidBottomAngleDeg debe bajar con theta", c.minValidBottomAngleDeg < 60.0)
        assertTrue("sticking velocity debe bajar con theta", c.stickingVelocityThresholdDegS < 10.0)
        assertTrue("sticking velocity no baja del piso", c.stickingVelocityThresholdDegS >= 6.0)
    }

    @Test
    fun gripCorrectionAbsorbsObservedGripRatio() {
        // Si el grip 2D observado es 3.0 (perspectiva mas exagerada), la
        // correccion debe ser 0.5 (clampeado al rango [0.45, 0.95]).
        val profile = BenchPressEnvironmentProfile(
            torsoTiltDegFromVertical = 20.0,
            shoulderWidthNormalized = 0.2,
            grip2DRatioAtRest = 3.0,
            upperArmToTorsoLengthRatio = 1.0,
            cameraYawHintDeg = 0.0
        )
        val c = BenchPressCalibratedThresholds.from(profile)
        assertEquals(0.5, c.gripPerspectiveCorrection, tol)
    }

    @Test
    fun gripCorrectionClampedAtMaximum() {
        // Si el grip 2D observado es muy bajo (~1.0), 1.5/1.0 = 1.5 cae fuera
        // del rango y debe ser clampeado a 0.95.
        val profile = BenchPressEnvironmentProfile(
            torsoTiltDegFromVertical = 0.0,
            shoulderWidthNormalized = 0.2,
            grip2DRatioAtRest = 1.0,
            upperArmToTorsoLengthRatio = 1.0,
            cameraYawHintDeg = 0.0
        )
        val c = BenchPressCalibratedThresholds.from(profile)
        assertEquals(0.95, c.gripPerspectiveCorrection, tol)
    }

    @Test
    fun abductionMaxKeepsMinimumOpenRange() {
        // Para theta extremo, asegurarse de que maxOk no se invierta debajo
        // de minOk + 25.
        val profile = BenchPressEnvironmentProfile(
            torsoTiltDegFromVertical = 55.0,
            shoulderWidthNormalized = 0.2,
            grip2DRatioAtRest = 2.5,
            upperArmToTorsoLengthRatio = 1.0,
            cameraYawHintDeg = 0.0
        )
        val c = BenchPressCalibratedThresholds.from(profile)
        assertTrue(
            "Rango OK debe mantenerse abierto >= 25 grados",
            c.abductionMaxOkDeg - c.abductionMinOkDeg >= 25.0 - 1e-6
        )
    }

    // ── Sanity ──────────────────────────────────────────────────────────────

    @Test
    fun sanityRejectsExtremeTorsoTilt() {
        val profile = BenchPressEnvironmentProfile(
            torsoTiltDegFromVertical = 70.0,
            shoulderWidthNormalized = 0.2,
            grip2DRatioAtRest = 2.5,
            upperArmToTorsoLengthRatio = 1.0,
            cameraYawHintDeg = 0.0
        )
        assertFalse(BenchPressCalibratedThresholds.isProfileSane(profile))
    }

    @Test
    fun sanityRejectsTooFarSubject() {
        val profile = BenchPressEnvironmentProfile(
            torsoTiltDegFromVertical = 10.0,
            shoulderWidthNormalized = 0.02,
            grip2DRatioAtRest = 2.5,
            upperArmToTorsoLengthRatio = 1.0,
            cameraYawHintDeg = 0.0
        )
        assertFalse(BenchPressCalibratedThresholds.isProfileSane(profile))
    }

    @Test
    fun sanityRejectsAnomalousGripRatio() {
        val profile = BenchPressEnvironmentProfile(
            torsoTiltDegFromVertical = 10.0,
            shoulderWidthNormalized = 0.2,
            grip2DRatioAtRest = 0.5,
            upperArmToTorsoLengthRatio = 1.0,
            cameraYawHintDeg = 0.0
        )
        assertFalse(BenchPressCalibratedThresholds.isProfileSane(profile))
    }

    @Test
    fun sanityAcceptsTypicalProfile() {
        val profile = BenchPressEnvironmentProfile(
            torsoTiltDegFromVertical = 25.0,
            shoulderWidthNormalized = 0.18,
            grip2DRatioAtRest = 2.4,
            upperArmToTorsoLengthRatio = 0.85,
            cameraYawHintDeg = 2.0
        )
        assertTrue(BenchPressCalibratedThresholds.isProfileSane(profile))
    }

    // ── Debug map ───────────────────────────────────────────────────────────

    @Test
    fun debugMapExposesAllFields() {
        val c = BenchPressCalibratedThresholds.canonical()
        val map = c.toDebugMap()
        assertTrue(map.containsKey("gripPerspectiveCorrection"))
        assertTrue(map.containsKey("topPositionAngleDeg"))
        assertTrue(map.containsKey("stickingVelocityThresholdDegS"))
        assertTrue(map.containsKey("abductionCriticalDeg"))
    }
}
