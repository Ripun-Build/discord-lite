package com.discordlite.app

import android.app.Application
import com.discordlite.app.data.AppContainer

class DiscordLiteApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
