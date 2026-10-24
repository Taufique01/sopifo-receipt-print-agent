package com.sopifo.printagent

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.sopifo.printagent.core.AppLog
import com.sopifo.printagent.core.CrashHandler
import com.sopifo.printagent.service.Notifications

class SopifoApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // Quick print → HTML draws an off-screen WebView; without this only its "visible" part is drawn.
        // Must run before any WebView exists.
        android.webkit.WebView.enableSlowWholeDocumentDraw()
        AppLog.init(filesDir)
        CrashHandler.install(this)
        container = AppContainer(this)
        Notifications.createChannels(this)
        AppLog.i("App", "Process started", "version" to BuildConfig.VERSION_NAME)
        // "App starts" is one of the pending-job recovery triggers.
        container.startAgent("app_start")
        // The app lock closes again whenever the app goes to the background.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) = container.appLock.lockNow()
        })
    }
}
