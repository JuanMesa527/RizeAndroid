package com.rize.rizeandroid.biomechanics.calibration

/**
 * Toggle global para activar/desactivar la calibracion por sesion del press de banca.
 *
 * Permite hacer A/B en el gym sin recompilar: con [calibrationEnabled] = false el
 * algoritmo opera contra los umbrales canonicos (banco plano frontal, equivalentes
 * a los valores hardcoded historicos). Con true, se aplica la transformacion
 * geometrica derivada del perfil capturado en READY.
 */
object BenchPressDebugSwitches {
    @Volatile
    var calibrationEnabled: Boolean = true

    @Volatile
    var emitDebugMap: Boolean = true
}
