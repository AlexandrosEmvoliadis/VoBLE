package com.example.neuralaudionode

import android.app.Application

class App : Application() {
    lateinit var engine: Engine
        private set

    override fun onCreate() {
        super.onCreate()
        engine = Engine(this)
        engine.init()
    }
}
