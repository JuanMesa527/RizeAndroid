package com.rize.rizeandroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

        assertTrue("repCount=${lastResult.repCount}, cvt=${lastResult.cvtPercent}, velocityLoss=${lastResult.velocityLossPercent}", lastResult.repCount >= 2)
        assertNotNull("velocityLoss=${lastResult.velocityLossPercent}", lastResult.velocityLossPercent)
        assertTrue("velocityLoss=${lastResult.velocityLossPercent}", (lastResult.velocityLossPercent ?: 0.0) > 20.0)
        assertTrue("fatigueDetected=${lastResult.fatigueDetected}, velocityLoss=${lastResult.velocityLossPercent}", lastResult.fatigueDetected)
    }

    @Test
    fun flagsDepthAndTrunkRiskOnShallowAndLeaningRep() {
        val algorithm = SquatBiomechanicsAlgorithm()
        var lastResult = AlgorithmResult()

        buildRepSequence(bottomKneeAngle = 85.0, bottomHipAngle = 45.0, downFrames = 20, upFrames = 20)
            .forEach { frame -> lastResult = algorithm.process(frame) }

        assertEquals("repCount=${lastResult.repCount}, cvt=${lastResult.cvtPercent}, velocityLoss=${lastResult.velocityLossPercent}", 1, lastResult.repCount)
        assertTrue("depthInsufficient=${lastResult.depthInsufficient}", lastResult.depthInsufficient)
        assertTrue("trunkLeanRisk=${lastResult.trunkLeanRisk}", lastResult.trunkLeanRisk)
        assertTrue("technicalError=${lastResult.technicalError}", lastResult.technicalError != ErrorLevel.NONE)
    }

    @Test
    fun classifiesDepthAtBoundaryAngles() {
        val algorithm = SquatBiomechanicsAlgorithm()

        val deep = runSingleRep(algorithm, bottomKneeAngle = 45.0, bottomHipAngle = 95.0)
        assertEquals(SquatDepthCategory.DEEP, deep.squatDepthCategory)
        assertFalse(deep.depthInsufficient)

        val medium = runSingleRep(algorithm, bottomKneeAngle = 70.0, bottomHipAngle = 95.0)
        assertEquals(SquatDepthCategory.MEDIUM, medium.squatDepthCategory)
        assertFalse(medium.depthInsufficient)

        val partial = runSingleRep(algorithm, bottomKneeAngle = 90.0, bottomHipAngle = 95.0)
        assertEquals(SquatDepthCategory.PARTIAL, partial.squatDepthCategory)
        assertTrue(partial.depthInsufficient)
    }

    @Test
    fun doesNotFlagTrunkRiskAt80DegBoundary() {
        val algorithm = SquatBiomechanicsAlgorithm()
        val result = runSingleRep(algorithm, bottomKneeAngle = 45.0, bottomHipAngle = 80.0)
        assertFalse("trunkLeanRisk=${result.trunkLeanRisk}", result.trunkLeanRisk)
    }

    @Test
    fun marksModerateAndSevereCvtThresholds() {
        val moderate = runReps(
            listOf(30.0, 32.0, 34.0),
            bottomHipAngle = 95.0
        )
        assertNotNull(moderate.cvtPercent)
        assertTrue("CVT=${moderate.cvtPercent}", (moderate.cvtPercent ?: 0.0) >= 5.0)
        assertTrue("CVT=${moderate.cvtPercent}", (moderate.cvtPercent ?: 0.0) <= 10.0)
        assertEquals(ErrorLevel.MODERATE, moderate.technicalError)

        val severe = runReps(
            listOf(30.0, 35.0, 40.0),
            bottomHipAngle = 95.0
        )
        assertNotNull(severe.cvtPercent)
        assertTrue("CVT=${severe.cvtPercent}", (severe.cvtPercent ?: 0.0) > 10.0)
        assertEquals(ErrorLevel.SEVERE, severe.technicalError)
    }

    @Test
    fun keepsStableAndModerateVelocityLossWithoutFatigueFlag() {
        val stable = runVelocityScenario(referenceUpFrames = 20, secondUpFrames = 21)
        assertNotNull(stable.velocityLossPercent)
        assertTrue("VL=${stable.velocityLossPercent}", (stable.velocityLossPercent ?: 100.0) < 10.0)
        assertFalse(stable.fatigueDetected)

        val moderateFatigueStart = runVelocityScenario(referenceUpFrames = 20, secondUpFrames = 24)
        assertNotNull(moderateFatigueStart.velocityLossPercent)
        assertTrue("VL=${moderateFatigueStart.velocityLossPercent}", (moderateFatigueStart.velocityLossPercent ?: 0.0) >= 10.0)
        assertTrue("VL=${moderateFatigueStart.velocityLossPercent}", (moderateFatigueStart.velocityLossPercent ?: 100.0) < 20.0)
        assertFalse(moderateFatigueStart.fatigueDetected)
    }

    private fun runSingleRep(
        algorithm: SquatBiomechanicsAlgorithm,
        bottomKneeAngle: Double,
        bottomHipAngle: Double,
        downFrames: Int = 20,
        upFrames: Int = 20
    ): AlgorithmResult {
        var result = AlgorithmResult()
        buildRepSequence(
            bottomKneeAngle = bottomKneeAngle,
            bottomHipAngle = bottomHipAngle,
            downFrames = downFrames,
            upFrames = upFrames
        ).forEach { frame -> result = algorithm.process(frame) }
        return result
    }

    private fun runReps(bottomKneeAngles: List<Double>, bottomHipAngle: Double): AlgorithmResult {
        val algorithm = SquatBiomechanicsAlgorithm()
        var lastResult = AlgorithmResult()
        bottomKneeAngles.forEach { knee ->
            lastResult = runSingleRep(
                algorithm = algorithm,
                bottomKneeAngle = knee,
                bottomHipAngle = bottomHipAngle
            )
        }
        return lastResult
    }

    private fun runVelocityScenario(referenceUpFrames: Int, secondUpFrames: Int): AlgorithmResult {
        val algorithm = SquatBiomechanicsAlgorithm()
        var result = AlgorithmResult()
        result = runSingleRep(
            algorithm = algorithm,
            bottomKneeAngle = 45.0,
            bottomHipAngle = 95.0,
            upFrames = referenceUpFrames
        )
        result = runSingleRep(
            algorithm = algorithm,
            bottomKneeAngle = 45.0,
            bottomHipAngle = 95.0,
            upFrames = secondUpFrames
        )
        return result
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


