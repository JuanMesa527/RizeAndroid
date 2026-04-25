package com.rize.rizeandroid

import kotlin.math.abs

/**
 * AngleFilter - Suavizado de ángulos para estabilizar valores de MediaPipe
 *
 * Implementa múltiples estrategias:
 * - Media móvil (Moving Average)
 * - Filtro Kalman simplificado
 * - Umbral de cambio mínimo (Dead zone)
 */
class AngleFilter(
    private val windowSize: Int = 5,           // Frames para media móvil
    private val minChangeThreshold: Double = 1.5 // Cambio mínimo en grados para actualizar
) {

    // ── Media Móvil ──────────────────────────────────────────────────────
    private val movingAverageWindow = mutableListOf<Double>()

    // ── Filtro Kalman ────────────────────────────────────────────────────
    private var kalmanEstimate: Double? = null
    private var kalmanErrorEstimate = 10.0   // Error inicial alto = desconfía al inicio
    private var kalmanProcessNoise = 0.1     // Ruido del proceso (qué tan "suave")
    private var kalmanMeasurementNoise = 2.0 // Ruido de medición (cuánto confía en MediaPipe)

    // ── Estado anterior ───────────────────────────────────────────────────
    private var lastFilteredValue: Double? = null
    private var lastRawValue: Double? = null

    /**
     * Aplica filtrado a un valor de ángulo crudo
     */
    fun filter(rawAngle: Double): Double {
        lastRawValue = rawAngle

        // Primero actualizamos el estado interno completo para que los cambios
        // pequeños sigan acumulándose en el suavizado y no se "congele" la señal.
        // El dead zone se aplica solo al valor final para evitar micro-jitter.
        val smoothed = applyMovingAverage(rawAngle)
        val filtered = applyKalmanFilter(smoothed)

        val stableValue = if (lastFilteredValue != null && abs(filtered - lastFilteredValue!!) < minChangeThreshold) {
            lastFilteredValue!!
        } else {
            filtered
        }

        lastFilteredValue = stableValue
        return stableValue
    }

    /**
     * Media móvil simple: promedia los últimos N valores
     */
    private fun applyMovingAverage(value: Double): Double {
        movingAverageWindow.add(value)

        // Mantén solo los últimos windowSize elementos
        if (movingAverageWindow.size > windowSize) {
            movingAverageWindow.removeAt(0)
        }

        return movingAverageWindow.average()
    }

    /**
     * Filtro Kalman 1D simplificado
     * Combina predicción con observación para suavizar sin lag
     */
    private fun applyKalmanFilter(measurement: Double): Double {
        if (kalmanEstimate == null) {
            // Inicialización: la primera medición es la estimación
            kalmanEstimate = measurement
            return measurement
        }

        // Paso 1: Predicción (sin dinámica, asumimos que el ángulo no cambia mucho)
        val predictedEstimate = kalmanEstimate!!
        val predictedErrorEstimate = kalmanErrorEstimate + kalmanProcessNoise

        // Paso 2: Ganancia de Kalman
        val kalmanGain = predictedErrorEstimate / (predictedErrorEstimate + kalmanMeasurementNoise)

        // Paso 3: Actualización (innovación = diferencia entre medición y predicción)
        kalmanEstimate = predictedEstimate + kalmanGain * (measurement - predictedEstimate)

        // Paso 4: Actualizar error estimado
        kalmanErrorEstimate = (1 - kalmanGain) * predictedErrorEstimate

        return kalmanEstimate!!
    }

    /**
     * Resetea el filtro (útil cuando comienza una nueva sesión)
     */
    fun reset() {
        movingAverageWindow.clear()
        kalmanEstimate = null
        kalmanErrorEstimate = 10.0
        lastFilteredValue = null
        lastRawValue = null
    }

    /**
     * Ajusta la sensibilidad del filtro Kalman
     * - Menor processNoise = más suave pero lento en cambios reales
     * - Mayor processNoise = más reactivo pero más ruidoso
     */
    fun setKalmanParameters(processNoise: Double, measurementNoise: Double) {
        kalmanProcessNoise = processNoise
        kalmanMeasurementNoise = measurementNoise
    }

    /**
     * Retorna el último valor filtrado
     */
    fun getLastFiltered(): Double? = lastFilteredValue

    /**
     * Retorna el último valor crudo
     */
    fun getLastRaw(): Double? = lastRawValue
}

