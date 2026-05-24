package com.rize.rizeandroid.biomechanics.calibration

/** Toggle global para activar/desactivar la calibracion por sesion del press de banca. */
object BenchPressDebugSwitches {
    @Volatile
    var calibrationEnabled: Boolean = true

    @Volatile
    var emitDebugMap: Boolean = true
}
