package com.sisa.app

import android.app.Application

class GemmaApp : Application() {
    override fun onCreate() {
        super.onCreate()
    }
}

typealias SisaApp = GemmaApp
