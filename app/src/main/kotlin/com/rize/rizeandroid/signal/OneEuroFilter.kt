package com.rize.rizeandroid.signal

import kotlin.math.PI
import kotlin.math.abs

/**
 * Implementacion del filtro One Euro, un filtro de paso bajo adaptativo que se ajusta a la velocidad de la señal.
 */
class OneEuroFilter(
    private val minCutoff: Double = DEFAULT_MIN_CUTOFF,
    private val beta: Double = DEFAULT_BETA,
    private val dCutoff: Double = DEFAULT_D_CUTOFF
) {

    /**
     * Constantes para los valores por defecto del filtro.
     */
    companion object {
        const val DEFAULT_MIN_CUTOFF = 1.0
        const val DEFAULT_BETA = 0.01
        const val DEFAULT_D_CUTOFF = 1.0

        // Si no llega un timestamp explicito, asumimos muestreo a 30 Hz.
        private const val FALLBACK_DT_SECONDS = 1.0 / 30.0

        // Guardrails para dt medido entre frames: fuera de este rango
        // es mas probable que el frame se haya saltado que un dt real.
        private const val MIN_DT_SECONDS = 1.0 / 120.0
        private const val MAX_DT_SECONDS = 0.2
    }

    private var xPrev: Double = 0.0
    private var dxPrev: Double = 0.0
    private var tPrevMs: Long = -1L
    private var initialized = false

    /** Devuelve el valor filtrado usando el timestamp de la muestra en milisegundos. */
    fun filter(value: Double, timestampMs: Long): Double {
        if (!initialized) {
            xPrev = value
            dxPrev = 0.0
            tPrevMs = timestampMs
            initialized = true
            return value
        }

        val dtMs = (timestampMs - tPrevMs).coerceAtLeast(1L)
        val dtS = (dtMs / 1000.0).coerceIn(MIN_DT_SECONDS, MAX_DT_SECONDS)

        val dxRaw = (value - xPrev) / dtS
        val dxAlpha = smoothingFactor(dtS, dCutoff)
        val dxHat = dxAlpha * dxRaw + (1.0 - dxAlpha) * dxPrev

        val cutoff = minCutoff + beta * abs(dxHat)
        val alpha = smoothingFactor(dtS, cutoff)
        val xHat = alpha * value + (1.0 - alpha) * xPrev

        xPrev = xHat
        dxPrev = dxHat
        tPrevMs = timestampMs
        return xHat
    }

    /** Devuelve el valor filtrado usando el timestamp de la muestra en milisegundos. */
    fun filter(value: Double): Double {
        val nextTs = if (tPrevMs < 0) 0L else tPrevMs + (FALLBACK_DT_SECONDS * 1000).toLong()
        return filter(value, nextTs)
    }

    /**
     * Reinicia el filtro.
     */
    fun reset() {
        xPrev = 0.0
        dxPrev = 0.0
        tPrevMs = -1L
        initialized = false
    }

    /**
     * Calcula el factor de suavizado para un dt y corte dados.
     */
    private fun smoothingFactor(dt: Double, cutoff: Double): Double {
        val tau = 1.0 / (2.0 * PI * cutoff)
        return 1.0 / (1.0 + tau / dt)
    }
}
