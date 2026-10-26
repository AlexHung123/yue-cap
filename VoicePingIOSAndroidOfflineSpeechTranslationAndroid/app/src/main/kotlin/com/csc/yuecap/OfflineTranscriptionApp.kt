package com.csc.yuecap

import android.app.Application
import com.csc.yuecap.data.AppDatabase
import com.csc.yuecap.data.AppPreferences
import com.csc.yuecap.service.WhisperEngine

class OfflineTranscriptionApp : Application() {

    lateinit var database: AppDatabase
        private set

    lateinit var preferences: AppPreferences
        private set

    lateinit var whisperEngine: WhisperEngine
        private set

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.getInstance(this)
        preferences = AppPreferences(this)
        whisperEngine = WhisperEngine(this, preferences)
    }

    override fun onTerminate() {
        super.onTerminate()
        whisperEngine.destroy()
    }
}
