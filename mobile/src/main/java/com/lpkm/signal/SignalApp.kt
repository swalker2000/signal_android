package com.lpkm.signal

import android.app.Application

class SignalApp : Application() {
    override fun onCreate() {
        super.onCreate()
        TelemetryService.ensureChannel(this)
    }
}
