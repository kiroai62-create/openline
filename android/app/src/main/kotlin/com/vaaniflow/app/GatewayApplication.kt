package com.vaaniflow.app

import android.app.Application
import com.vaaniflow.app.audio.CallAudioBridge
import com.vaaniflow.app.transport.GatewayConnectionService
import com.vaaniflow.app.transport.GatewayRuntime

class GatewayApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CallAudioBridge.initialize(this)
        GatewayRuntime.initialize(this)
        GatewayConnectionService.start(this)
    }
}
