package com.rize.rizeandroid.signal

/** Suavizador temporal para los 33 landmarks de MediaPipe Pose.
 *
 * Aplica un filtro 1€ independiente a cada componente (x, y, z) de cada
 * landmark, y un EMA ligero sobre visibility. Recibe el flat-list de 132
 * Doubles que produce CameraView y devuelve otro flat-list del mismo
 * tamano con los valores filtrados.
 *
 * El suavizado es crucial para:
 *   - eliminar el jitter inter-frame de MediaPipe (~1-3 px equivalentes)
 *   - evitar que la diferencia finita de velocidad angular amplifique ruido
 *   - hacer que los umbrales de simetria y velocidad sean aplicables
 *
 * Parametros por defecto (perfil conservador):
 *   minCutoff = 1.0 Hz   — bajo = muy suave cuando la persona esta quieta
 *   beta      = 0.01     — sube el corte cuando hay movimiento real
 *   dCutoff   = 1.0 Hz   — derivador interno
 *
 * Estos parametros son apropiados para los tres ejercicios soportados.
 * Para press banca (vista frontal, mas ruido) se podria subir minCutoff
 * a 1.5 Hz y beta a 0.02 si se observa sobre-suavizado.
 *
 * [reset] debe llamarse cada vez que cambia el ejercicio activo o se
 * reanuda la captura tras un switch de camara.
 */
class LandmarkSmoother(
    minCutoff: Double = OneEuroFilter.DEFAULT_MIN_CUTOFF,
    beta: Double = OneEuroFilter.DEFAULT_BETA,
    dCutoff: Double = OneEuroFilter.DEFAULT_D_CUTOFF
) {

    companion object {
        const val LANDMARK_COUNT = 33
        const val COMPONENTS_PER_LANDMARK = 4 // x, y, z, visibility
        const val FLAT_LIST_SIZE = LANDMARK_COUNT * COMPONENTS_PER_LANDMARK

        // Peso del EMA de visibility. Alto = rastrea rapido la visibilidad real,
        // bajo = protege contra perdidas momentaneas de landmark.
        private const val VISIBILITY_ALPHA = 0.4f
    }

    // 33 landmarks × 3 ejes = 99 filtros escalares
    private val filtersXYZ: Array<OneEuroFilter> = Array(LANDMARK_COUNT * 3) {
        OneEuroFilter(minCutoff, beta, dCutoff)
    }

    // Visibility: EMA ligero por landmark (33 valores)
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
