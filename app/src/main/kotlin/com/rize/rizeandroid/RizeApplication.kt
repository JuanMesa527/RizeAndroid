package com.rize.rizeandroid

import android.app.Application
import com.rize.rizeandroid.data.RizeDatabase
import com.rize.rizeandroid.data.SessionRepository

class RizeApplication : Application() {

    val database: RizeDatabase by lazy { RizeDatabase.getInstance(this) }
    val sessionRepository: SessionRepository by lazy { SessionRepository(database) }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        @Volatile
        private var instance: RizeApplication? = null

        @JvmStatic
        fun get(): RizeApplication =
            instance ?: error("RizeApplication not initialized")
    }
}
