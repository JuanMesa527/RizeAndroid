package com.rize.rizeandroid.signal

import kotlin.math.PI
import kotlin.math.abs

/**
 * Filtro 1€ (One-Euro) de Casiez, Roussel y Vogel (CHI 2012).
 *
 * Paper: "1€ Filter: A Simple Speed-based Low-pass Filter for Noisy Input
 *        in Interactive Systems". https://hal.inria.fr/hal-00670496
 *
 * Se comporta como un pasabajos cuya frecuencia de corte se adapta a la
 * velocidad de la señal: corte bajo cuando la señal esta quieta (mata jitter)
 * y corte alto cuando se mueve rapido (deja pasar transitorios reales).
 *
 * Parametros:
 *   minCutoff — frecuencia de corte minima (Hz). Mas bajo = mas suave en reposo.
 *   beta      — ganancia adaptativa (sensibilidad a la velocidad). Mas alto =
 *               menos latencia en movimientos rapidos.
 *   dCutoff   — frecuencia de corte del derivador interno (Hz). Se deja en 1.0.
 *
 * Valores de partida para landmarks de MediaPipe a 30 Hz:
 *   minCutoff = 1.0 Hz
 *   beta      = 0.01
 *   dCutoff   = 1.0 Hz
 *
 * El filtro es stateful: una instancia por señal escalar a suavizar.
 * Si se reinicia el ejercicio o cambia la camara, llamar [reset].
 */
class OneEuroFilter(
    private val minCutoff: Double = DEFAULT_MIN_CUTOFF,
    private val beta: Double = DEFAULT_BETA,
    private val dCutoff: Double = DEFAULT_D_CUTOFF
) {

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

        // Estimador de derivada filtrado para calcular la velocidad instantanea
        val dxRaw = (value - xPrev) / dtS
        val dxAlpha = smoothingFactor(dtS, dCutoff)
        val dxHat = dxAlpha * dxRaw + (1.0 - dxAlpha) * dxPrev

        // Cutoff adaptativo: mas alto cuando la senal cambia rapido
        val cutoff = minCutoff + beta * abs(dxHat)
        val alpha = smoothingFactor(dtS, cutoff)
        val xHat = alpha * value + (1.0 - alpha) * xPrev

        xPrev = xHat
        dxPrev = dxHat
        tPrevMs = timestampMs
        return xHat
    }

    /** Conveniencia: filtra sin timestamp externo, asumiendo dt constante de 1/30 s. */
    fun filter(value: Double): Double {
        val nextTs = if (tPrevMs < 0) 0L else tPrevMs + (FALLBACK_DT_SECONDS * 1000).toLong()
        return filter(value, nextTs)
    }

    fun reset() {
        xPrev = 0.0
        dxPrev = 0.0
        tPrevMs = -1L
        initialized = false
    }

    private fun smoothingFactor(dt: Double, cutoff: Double): Double {
        val tau = 1.0 / (2.0 * PI * cutoff)
        return 1.0 / (1.0 + tau / dt)
    }
}
