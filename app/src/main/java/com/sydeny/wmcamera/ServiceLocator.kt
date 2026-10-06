package com.sydeny.wmcamera

import android.app.Application
import androidx.datastore.preferences.preferencesDataStore
import com.sydeny.wmcamera.data.LogoStore
import com.sydeny.wmcamera.data.SettingsRepository
import com.sydeny.wmcamera.domain.LocationStatus
import com.sydeny.wmcamera.domain.WatermarkConfig
import com.sydeny.wmcamera.domain.clamped
import com.sydeny.wmcamera.render.LocationProvider
import com.sydeny.wmcamera.render.ReGeocoder
import com.sydeny.wmcamera.render.WatermarkPainter

/**
 * 极简的手写依赖容器。
 *
 * 没上 Hilt：这个 App 只有一个进程、两个 ViewModel、六七个单例，
 * 为此引入 kapt/KSP 纯属自找构建麻烦。
 */
object ServiceLocator {

    private lateinit var app: Application

    fun init(application: Application) {
        app = application
    }

    val applicationContext: Application get() = app

    val settingsRepository: SettingsRepository by lazy {
        SettingsRepository(app.preferencesStore)
    }

    val logoStore: LogoStore by lazy { LogoStore(app) }

    val reGeocoder: ReGeocoder by lazy { ReGeocoder(app) }

    val locationProvider: LocationProvider by lazy {
        LocationProvider(app, reGeocoder = reGeocoder)
    }

    val painter: WatermarkPainter by lazy {
        WatermarkPainter(
            logoProvider = { fileName -> logoStore.load(fileName) },
            statusTextProvider = { status ->
                app.getString(
                    when (status) {
                        LocationStatus.LOCATING -> R.string.status_locating
                        LocationStatus.NO_PERMISSION -> R.string.status_no_permission
                        LocationStatus.NO_SIGNAL -> R.string.status_no_signal
                        LocationStatus.FIXED -> R.string.none
                    },
                )
            },
        )
    }

    val defaultConfig: WatermarkConfig = WatermarkConfig().clamped()
}

private val Application.preferencesStore by preferencesDataStore(name = "watermark_settings")
