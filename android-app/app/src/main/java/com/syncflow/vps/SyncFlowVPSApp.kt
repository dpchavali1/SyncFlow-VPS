package com.syncflow.vps

import android.app.Application
import com.syncflow.vps.services.SyncFlowService

class SyncFlowVPSApp : Application() {
    val syncFlowService: SyncFlowService by lazy {
        SyncFlowService.getInstance(this)
    }

    override fun onCreate() {
        super.onCreate()
    }
}
