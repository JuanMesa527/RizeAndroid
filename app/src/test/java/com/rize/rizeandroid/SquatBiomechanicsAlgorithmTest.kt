package com.rize.rizeandroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class SquatBiomechanicsAlgorithmTest {

    @Test
    fun detectsFatigueWhenConcentricVelocityDropsMoreThan20Percent() {
        val algorithm = SquatBiomechanicsAlgorithm()
        var lastResult = AlgorithmResult()

        // Rep 1: subida rapida (referencia de velocidad)
        buildRepSequence(bottomKneeAngle = 50.0, bottomHipAngle = 85.0, downFrames = 20, upFrames = 20)
            .forEach { frame -> lastResult = algorithm.process(frame) }

        // Rep 2: subida mas lenta -> perdida de velocidad > 20%
        buildRepSequence(bottomKneeAngle = 50.0, bottomHipAngle = 85.0, downFrames = 20, upFrames = 45)
            .forEach { frame -> lastResult = algorithm.process(frame) }

        assertTrue(lastResult.repCount >= 2)
        assertNotNull(lastResult.velocityLossPercent)
        assertTrue((lastResult.velocityLossPercent ?: 0.0) > 20.0)
        assertTrue(lastResult.fatigueDetected)
    }

    @Test
    fun flagsDepthAndTrunkRiskOnShallowAndLeaningRep() {
        val algorithm = SquatBiomechanicsAlgorithm()
        var lastResult = AlgorithmResult()

        buildRepSequence(bottomKneeAngle = 60.0, bottomHipAngle = 70.0, downFrames = 20, upFrames = 20)
            .forEach { frame -> lastResult = algorithm.process(frame) }

        assertEquals(1, lastResult.repCount)
        assertTrue(lastResult.depthInsufficient)
        assertTrue(lastResult.trunkLeanRisk)
        assertTrue(lastResult.technicalError != ErrorLevel.NONE)
    }

    private fun buildRepSequence(
        bottomKneeAngle: Double,
        bottomHipAngle: Double,
        downFrames: Int,
        upFrames: Int
    ): List<List<Double>> {
        val topKneeAngle = 170.0
        val topHipAngle = 120.0

        val frames = mutableListOf<List<Double>>()

        repeat(5) {
            frames += makeFrame(kneeAngleDeg = topKneeAngle, hipAngleDeg = topHipAngle)
        }

        for (i in 0..downFrames) {
            val t = i / downFrames.toDouble()
            val knee = lerp(topKneeAngle, bottomKneeAngle, t)
            val hip = lerp(topHipAngle, bottomHipAngle, t)
            frames += makeFrame(kneeAngleDeg = knee, hipAngleDeg = hip)
        }

        for (i in 0..upFrames) {
            val t = i / upFrames.toDouble()
            val knee = lerp(bottomKneeAngle, topKneeAngle, t)
            val hip = lerp(bottomHipAngle, topHipAngle, t)
            frames += makeFrame(kneeAngleDeg = knee, hipAngleDeg = hip)
        }

        repeat(5) {
            frames += makeFrame(kneeAngleDeg = topKneeAngle, hipAngleDeg = topHipAngle)
        }

        return frames
    }

    private fun makeFrame(kneeAngleDeg: Double, hipAngleDeg: Double): List<Double> {
        val landmarks = MutableList(33 * 4) { 0.0 }

        val shoulder = shoulderFromHipAngleDeg(hipAngleDeg)
        val hip = Pair(0.0, 1.0)
        val knee = Pair(0.0, 0.0)
        val ankle = ankleFromKneeAngleDeg(kneeAngleDeg)

        setLandmark(landmarks, 24, hip.first, hip.second, 0.0, 0.99)      // right hip
        setLandmark(landmarks, 26, knee.first, knee.second, 0.0, 0.99)    // right knee
        setLandmark(landmarks, 28, ankle.first, ankle.second, 0.0, 0.99)  // right ankle
        setLandmark(landmarks, 12, shoulder.first, shoulder.second, 0.0, 0.99) // right shoulder

        return landmarks
    }

    private fun ankleFromKneeAngleDeg(angleDeg: Double): Pair<Double, Double> {
        val radians = angleDeg * PI / 180.0
        val x = sin(radians)
        val y = cos(radians)
        return Pair(x, y)
    }

    private fun shoulderFromHipAngleDeg(angleDeg: Double): Pair<Double, Double> {
        val radians = angleDeg * PI / 180.0
        val x = sin(radians)
        val y = 1.0 - cos(radians)
        return Pair(x, y)
    }

    private fun setLandmark(buffer: MutableList<Double>, index: Int, x: Double, y: Double, z: Double, visibility: Double) {
        val base = index * 4
        buffer[base] = x
        buffer[base + 1] = y
        buffer[base + 2] = z
        buffer[base + 3] = visibility
    }

    private fun lerp(start: Double, end: Double, t: Double): Double = start + (end - start) * t
}


