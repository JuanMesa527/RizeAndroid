package com.rize.rizeandroid.data

/**
 * Un par de singleton para mantener el estado de una sesión pendiente. Esto es útil para manejar casos donde se inicia una sesión pero no se completa, como cuando el usuario abandona la aplicación o cierra la pantalla antes de finalizar la sesión.
 * El `PendingSessionHolder` permite almacenar temporalmente los datos de la sesión pendiente y proporciona métodos para establecer, consumir, revisar y limpiar esos datos. 
 * Esto facilita la gestión de sesiones incompletas y permite que la aplicación maneje adecuadamente estos casos sin perder información importante.
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
