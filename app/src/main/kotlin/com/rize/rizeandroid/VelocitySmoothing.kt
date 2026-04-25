package com.rize.rizeandroid

import kotlin.math.abs

/**
 * VelocitySmoothing - Suavizado de velocidad angular
 *
 * Detecta cambios bruscos en velocidad que podrían ser ruido
 */
class VelocitySmoothing(
    private val windowSize: Int = 3,
    private val outlierThreshold: Double = 50.0 // deg/s - cambios mayores se ignoran como outliers
) {

    private val velocityWindow = mutableListOf<Double>()
    private var lastSmoothedVelocity: Double? = null

    /**
     * Aplica suavizado a velocidad angular
     */
    fun smooth(rawVelocity: Double): Double {
        val previous = lastSmoothedVelocity

        // Solo tratamos como outlier los saltos grandes que ocurren sin cambio
        // de dirección. Un cambio de signo es esperado en sentadilla (descenso
        // -> ascenso), así que no debe quedar bloqueado por este filtro.
        if (previous != null) {
            val sameDirection = rawVelocity == 0.0 || previous == 0.0 || (rawVelocity > 0.0) == (previous > 0.0)
            if (sameDirection && abs(rawVelocity - previous) > outlierThreshold) {
                return previous
            }
        }

        velocityWindow.add(rawVelocity)

        if (velocityWindow.size > windowSize) {
            velocityWindow.removeAt(0)
        }

        lastSmoothedVelocity = velocityWindow.average()
        return lastSmoothedVelocity!!
    }

    fun reset() {
        velocityWindow.clear()
        lastSmoothedVelocity = null
    }

    fun getLast(): Double? = lastSmoothedVelocity
}

