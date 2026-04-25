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
            // Evita clasificar como outlier el primer cambio real desde reposo
            // (p. ej. 0 -> -180 deg/s al iniciar descenso).
            val sameDirection = rawVelocity * previous > 0.0
            val previousIsStable = abs(previous) >= 1.0
            if (previousIsStable && sameDirection && abs(rawVelocity - previous) > outlierThreshold) {
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

