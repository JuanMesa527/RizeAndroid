package com.rize.rizeandroid.biomechanics.calibration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchPressCalibratorTest {

    @Test
    fun disabledKeepsCanonical() {
        val calibrator = BenchPressCalibrator(enabled = false)
        assertEquals(BenchPressCalibrator.State.DISABLED, calibrator.state)
        // Aun alimentandolo con frames perfectos no debe cambiar.
        repeat(50) {
            calibrator.onReadyFrame(syntheticFlatLandmarks(tiltDeg = 30.0), 160.0)
        }
        assertEquals(BenchPressCalibrator.State.DISABLED, calibrator.state)
        assertEquals(BenchPressCalibratedThresholds.canonical(), calibrator.thresholds)
    }

    @Test
    fun nonLockoutFramesDoNotCommit() {
        val calibrator = BenchPressCalibrator(enabled = true)
        repeat(50) {
            // Codo a 100 grados: no es lockout. No deberia acumular ventana
            // suficiente para commitear.
            calibrator.onReadyFrame(syntheticFlatLandmarks(tiltDeg = 0.0), 100.0)
        }
        assertNotEquals(BenchPressCalibrator.State.COMMITTED, calibrator.state)
        assertEquals(BenchPressCalibratedThresholds.canonical(), calibrator.thresholds)
    }

    @Test
    fun commitsAfterEnoughStableLockoutFrames() {
        val calibrator = BenchPressCalibrator(enabled = true)
        // Alimentamos suficientes frames en lockout (170 grados) y theta = 0.
        repeat(BenchPressCalibrator.COLLECT_WINDOW + 2) {
            calibrator.onReadyFrame(syntheticFlatLandmarks(tiltDeg = 0.0), 170.0)
        }
        assertEquals(BenchPressCalibrator.State.COMMITTED, calibrator.state)
        // theta = 0 -> umbrales ~ canonicos.
        assertEquals(
            BenchPressCalibratedThresholds.canonical().topPositionAngleDeg,
            calibrator.thresholds.topPositionAngleDeg,
            0.5
        )
    }

    @Test
    fun resetReturnsToCanonical() {
        val calibrator = BenchPressCalibrator(enabled = true)
        repeat(BenchPressCalibrator.COLLECT_WINDOW + 2) {
            calibrator.onReadyFrame(syntheticFlatLandmarks(tiltDeg = 20.0), 170.0)
        }
        assertEquals(BenchPressCalibrator.State.COMMITTED, calibrator.state)
        calibrator.reset()
        assertEquals(BenchPressCalibrator.State.WAITING, calibrator.state)
        assertEquals(BenchPressCalibratedThresholds.canonical(), calibrator.thresholds)
    }

    /**
     * Genera una lista plana de landmarks tipo MediaPipe (33 puntos x 4 valores).
     * Sintetiza una postura de press de banca canonica con torso inclinado
     * [tiltDeg] grados respecto a la vertical de la imagen.
     */
    private fun syntheticFlatLandmarks(tiltDeg: Double): List<Double> {
        val flat = MutableList(33 * 4) { 0.0 }
        // Visibilidad alta en todos los landmarks relevantes.
        fun setLm(idx: Int, x: Double, y: Double, z: Double = 0.0, vis: Double = 0.95) {
            val base = idx * 4
            flat[base] = x
            flat[base + 1] = y
            flat[base + 2] = z
            flat[base + 3] = vis
        }
        // Hombros centrados, ancho biacromial ~ 0.2 (normalizado).
        setLm(11, 0.40, 0.40) // shoulder L
        setLm(12, 0.60, 0.40) // shoulder R

        // Caderas desplazadas segun tilt. dy fijo en 0.3, dx = dy * tan(tilt).
        val dy = 0.30
        val dx = dy * Math.tan(Math.toRadians(tiltDeg))
        setLm(23, 0.40 + dx, 0.40 + dy) // hip L
        setLm(24, 0.60 + dx, 0.40 + dy) // hip R

        // Codos y munecas en lockout: arriba de los hombros (Y menor en coords
        // de imagen). Grip a 1.5x el ancho biacromial -> ancho munecas = 0.30.
        // Con perspectiva, las munecas suelen verse mas anchas, pero para el
        // test de "theta=0 ~ canonical" usamos grip 2D = 2.5x (lo que produce
        // gripCorrection = 1.5/2.5 = 0.6 = canonical).
        // Ancho biacromial = 0.2. Para grip 2D = 2.5: ancho munecas = 0.5.
        // Codos algo arriba y separados para que el ratio upperArm/torso quede
        // en rango sano [0.6, 1.8]. Upper arm L = sqrt(0.10^2 + 0.25^2) = 0.269.
        // Torso L (tilt=0) = 0.30 -> ratio 0.897. Torso L (tilt=30) ~ 0.346
        // -> ratio 0.778. Ambos casos sanos.
        setLm(13, 0.30, 0.15) // elbow L
        setLm(14, 0.70, 0.15) // elbow R
        setLm(15, 0.25, 0.05) // wrist L (mas afuera para grip 2D 2.5x)
        setLm(16, 0.75, 0.05) // wrist R

        return flat
    }
}
