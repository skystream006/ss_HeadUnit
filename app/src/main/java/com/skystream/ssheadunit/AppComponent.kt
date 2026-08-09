package com.skystream.ssheadunit

import android.app.NotificationManager
import android.content.Context
import android.net.wifi.WifiManager
import com.skystream.ssheadunit.connection.CommManager
import com.skystream.ssheadunit.connection.carkey.CarKeysManager
import com.skystream.ssheadunit.decoder.AudioDecoder
import com.skystream.ssheadunit.decoder.VideoDecoder
import com.skystream.ssheadunit.utils.SUExecutor
import com.skystream.ssheadunit.utils.Settings

class AppComponent(private val app: App) {

    val settings = Settings(app)
    val videoDecoder = VideoDecoder(settings)
    val audioDecoder = AudioDecoder()

    val notificationManager: NotificationManager
        get() = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val wifiManager: WifiManager
        get() = app.getSystemService(Context.WIFI_SERVICE) as WifiManager

    val commManager = CommManager(app, settings, audioDecoder, videoDecoder)

    val suExecutor = SUExecutor()

    val carKeysManager = CarKeysManager()
}
