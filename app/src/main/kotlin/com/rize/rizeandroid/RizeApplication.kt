package com.rize.rizeandroid

import android.app.Application
import com.rize.rizeandroid.data.RizeDatabase
import com.rize.rizeandroid.data.SessionRepository

/**
 * Clase principal de la aplicación Rize.
 */
class RizeApplication : Application() {

    val database: RizeDatabase by lazy { RizeDatabase.getInstance(this) }
    val sessionRepository: SessionRepository by lazy { SessionRepository(database) }

    /**
     * Método llamado cuando se crea la aplicación.
     */
    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    /**
     * Objeto complementario para gestionar la instancia única de la aplicación.
     */
    companion object {
        @Volatile
        private var instance: RizeApplication? = null

        @JvmStatic
        fun get(): RizeApplication =
            instance ?: error("RizeApplication not initialized")
    }
}
