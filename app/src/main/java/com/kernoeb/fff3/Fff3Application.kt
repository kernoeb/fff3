package com.kernoeb.fff3

import android.app.Application

class Fff3Application : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
