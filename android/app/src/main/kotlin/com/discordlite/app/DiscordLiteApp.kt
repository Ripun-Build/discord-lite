package com.discordlite.app

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.discordlite.app.data.AppContainer
import kotlinx.coroutines.launch

class DiscordLiteApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Live connection while the app is on screen; push notifications cover the background.
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    container.scope.launch { container.startSession() }
                }

                override fun onStop(owner: LifecycleOwner) {
                    container.gateway.stop()
                }
            },
        )
    }
}
