package com.sopifo.printagent

import android.app.Application
import com.sopifo.printagent.core.AppLog
import com.sopifo.printagent.core.CrashHandler
import com.sopifo.printagent.service.Notifications

class SopifoApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        AppLog.init(filesDir)
        CrashHandler.install(this)
        container = AppContainer(this)
        Notifications.createChannels(this)
        AppLog.i("App", "Process started", "version" to BuildConfig.VERSION_NAME)
        // "App starts" is one of the pending-job recovery triggers.
        container.startAgent("app_start")
    }
}
