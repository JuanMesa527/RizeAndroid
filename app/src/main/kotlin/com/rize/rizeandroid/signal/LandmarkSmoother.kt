package com.rize.rizeandroid.signal

/**
 * Aplica un filtro de suavizado a los landmarks de MediaPipe. Cada componente (x, y, z) de cada landmark se filtra con un OneEuroFilter independiente, y la 
 * visibilidad se suaviza con un EMA ligero para evitar fluctuaciones bruscas. El filtro se aplica a una lista plana de 132 valores 
 * (33 landmarks × 4 componentes cada uno). Si la lista de entrada no tiene el tamaño esperado, se devuelve sin modificar.
 */
class LandmarkSmoother(
    minCutoff: Double = OneEuroFilter.DEFAULT_MIN_CUTOFF,
    beta: Double = OneEuroFilter.DEFAULT_BETA,
    dCutoff: Double = OneEuroFilter.DEFAULT_D_CUTOFF
) {

    /**
     * Constantes para el número de landmarks, componentes por landmark y tamaño total de la lista plana. También se define un alpha para el suavizado de visibilidad.
     */
    companion object {
        const val LANDMARK_COUNT = 33
        const val COMPONENTS_PER_LANDMARK = 4 
        const val FLAT_LIST_SIZE = LANDMARK_COUNT * COMPONENTS_PER_LANDMARK

      
        private const val VISIBILITY_ALPHA = 0.4f
    }

    /**
     * Filtros para los componentes x, y, z de cada landmark.
     */
    private val filtersXYZ: Array<OneEuroFilter> = Array(LANDMARK_COUNT * 3) {
        OneEuroFilter(minCutoff, beta, dCutoff)
    }

    private val visibilityEma: FloatArray = FloatArray(LANDMARK_COUNT) { -1f }

    /**
     * Filtra la lista plana de landmarks. Si el tamano no es 132, devuelve la
     * entrada sin tocar para no romper el pipeline rio abajo.
     */
    fun filter(flat: List<Double>, timestampMs: Long): List<Double> {
        if (flat.size < FLAT_LIST_SIZE) return flat

        val out = ArrayList<Double>(FLAT_LIST_SIZE)
        for (i in 0 until LANDMARK_COUNT) {
            val base = i * COMPONENTS_PER_LANDMARK
            val rawX = flat[base]
            val rawY = flat[base + 1]
            val rawZ = flat[base + 2]
            val rawVis = flat[base + 3].toFloat()

            val fx = filtersXYZ[i * 3 + 0].filter(rawX, timestampMs)
            val fy = filtersXYZ[i * 3 + 1].filter(rawY, timestampMs)
            val fz = filtersXYZ[i * 3 + 2].filter(rawZ, timestampMs)

            val smoothedVis: Float = if (visibilityEma[i] < 0f) {
                visibilityEma[i] = rawVis
                rawVis
            } else {
                val v = VISIBILITY_ALPHA * rawVis + (1f - VISIBILITY_ALPHA) * visibilityEma[i]
                visibilityEma[i] = v
                v
            }

            out.add(fx)
            out.add(fy)
            out.add(fz)
            out.add(smoothedVis.toDouble())
        }
        return out
    }

    fun reset() {
        filtersXYZ.forEach { it.reset() }
        for (i in visibilityEma.indices) visibilityEma[i] = -1f
    }
}
