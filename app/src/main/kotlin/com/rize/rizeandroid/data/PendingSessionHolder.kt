package com.rize.rizeandroid.data

/**
 * Singleton in-memory para pasar datos de sesión entre CameraActivity y
 * SummaryActivity cuando el guardado es manual (auto-save desactivado). Si el
 * proceso muere antes de que el usuario decida guardar/descartar, los datos se
 * pierden — es una decisión consciente: simplicidad > robustez en este edge.
 */
object PendingSessionHolder {
    @Volatile
    var pending: PendingSessionData? = null

    fun set(data: PendingSessionData) {
        pending = data
    }

    fun consume(): PendingSessionData? {
        val current = pending
        pending = null
        return current
    }

    fun peek(): PendingSessionData? = pending

    fun clear() {
        pending = null
    }
}
