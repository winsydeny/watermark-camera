package com.sydeny.wmcamera

import android.app.Application

class WatermarkCameraApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
    }
}
