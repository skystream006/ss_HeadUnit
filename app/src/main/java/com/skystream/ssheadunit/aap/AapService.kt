package com.skystream.ssheadunit.aap

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.app.UiModeManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.skystream.ssheadunit.App
import com.skystream.ssheadunit.app.BootCompleteReceiver
import com.skystream.ssheadunit.main.MainActivity
import com.skystream.ssheadunit.R
import com.skystream.ssheadunit.utils.AppLog
import com.skystream.ssheadunit.utils.AppPermissions
import com.skystream.ssheadunit.utils.ToastUtils
import com.skystream.ssheadunit.aap.protocol.messages.NightModeEvent
import com.skystream.ssheadunit.aap.protocol.proto.MediaPlayback
import com.skystream.ssheadunit.connection.CommManager
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.session.MediaButtonReceiver
import com.skystream.ssheadunit.connection.UsbAccessoryMode
import com.skystream.ssheadunit.connection.UsbDeviceCompat
import com.skystream.ssheadunit.connection.UsbReceiver
import com.skystream.ssheadunit.location.GpsLocationService
import com.skystream.ssheadunit.utils.HeadUnitScreenConfig
import com.skystream.ssheadunit.utils.LocaleHelper
import com.skystream.ssheadunit.utils.LogExporter
import com.skystream.ssheadunit.utils.NightModeManager
import kotlinx.coroutines.*
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.provider.Settings as AndroidSettings
import android.view.View
import android.view.WindowManager
import android.media.AudioManager
import com.skystream.ssheadunit.connection.CarKeyReceiver
import com.skystream.ssheadunit.connection.carkey.CarKeysManager
import com.skystream.ssheadunit.main.BackgroundNotification
import com.skystream.ssheadunit.utils.SUExecutor
import com.skystream.ssheadunit.utils.Settings
import com.skystream.ssheadunit.utils.protoUint32ToLong

/**
 * Top-level foreground service that manages the Android Auto USB connection lifecycle.
 */
class AapService : Service(), UsbReceiver.Listener {

    // SupervisorJob prevents a child coroutine failure from cancelling the whole scope
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var uiModeManager: UiModeManager
    private lateinit var usbReceiver: UsbReceiver
    private var nightModeManager: NightModeManager? = null
    private var mediaSession: MediaSessionCompat? = null

    private inline fun <T> safeMediaSessionCall(crossinline block: (MediaSessionCompat) -> T): T? {
        if (isDestroying) return null
        val session = mediaSession ?: return null
        return try {
            block(session)
        } catch (e: Exception) {
            // Catching binder death: DeadObjectException or DeadSystemException
            AppLog.e("MediaSession call failed (Binder dead?): ${e.message}")
            null
        }
    }
    private var permanentFocusRequest: android.media.AudioFocusRequest? = null

    private var lastAaMediaMetadata: MediaPlayback.MediaMetaData? = null
    private var lastAaPlaybackPositionMs: Long = 0L
    private var lastAaPlaybackIsPlaying: Boolean? = null
    private var mediaSessionIsPlaying = false
    private var mediaMetadataDecodeJob: Job? = null
    /** Decoded on a background thread in [scheduleApplyAaMediaMetadata]; reused for notification updates on position ticks. */
    private var cachedAaAlbumArtBitmap: Bitmap? = null
    private var settingsPrefs: SharedPreferences? = null
    private val settings: Settings by lazy { App.provide(this).settings }
    private val mediaNotification by lazy { BackgroundNotification(this) }

    private val settingsPreferenceListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == Settings.KEY_SYNC_MEDIA_SESSION_AA_METADATA) {
                serviceScope.launch(Dispatchers.Main) {
                    refreshMediaSessionMetadataForPrefsChange()
                }
            }

            if (key == Settings.KEY_LOG_SOURCE || key == Settings.KEY_LOG_LEVEL || key == Settings.KEY_LOG_CAPTURE_ENABLED) {
                serviceScope.launch(Dispatchers.IO) {
                    try {
                        syncLogBackendState()
                    } catch (e: Exception) {
                        AppLog.e("LogExporter: failed to sync state", e)
                    }
                }
            }

            if (key == Settings.KEY_MEDIA_VOLUME_OFFSET || key == Settings.KEY_ASSISTANT_VOLUME_OFFSET || key == Settings.KEY_NAVIGATION_VOLUME_OFFSET) {
                serviceScope.launch(Dispatchers.Main) {
                    commManager.updateAudioGains()
                }
            }
        }

    private fun syncLogBackendState() {
        AppLog.init(settings, this@AapService)

        if (settings.logSource == Settings.LogSource.APPLOG_FILE) {
            if (LogExporter.isCapturing) {
                LogExporter.stopCapture()
                AppLog.d("LogExporter: stopped because logSource=APPLOG_FILE")
            }
            return
        }

        val newLogLevel = settings.exporterLogLevel
        val exporterCaptureEnabled = settings.exporterCaptureEnabled
        val isCapturing = LogExporter.isCapturing
        val currentLogLevel = LogExporter.currentLevel

        if (!exporterCaptureEnabled || newLogLevel == LogExporter.LogLevel.SILENT) {
            if (isCapturing) {
                LogExporter.stopCapture()
                AppLog.d("LogExporter: stopped (enabled=$exporterCaptureEnabled, level=${newLogLevel.name})")
            }
        } else if (!isCapturing || currentLogLevel != newLogLevel) {
            LogExporter.startCapture(this@AapService, newLogLevel)
            AppLog.d("LogExporter: started with level ${newLogLevel.name}")
        }
    }

    /**
     * Set to `true` before calling [stopSelf] or entering [onDestroy] to suppress any
     * flow observers that would otherwise update the already-dismissed notification.
     */
    private var isDestroying = false
    private var hasEverConnected = false
    private var accessoryHandshakeFailures = 0
    /**
     * Partial wake lock acquired when the service starts from boot/screen-on.
     * Keeps the CPU active while the head unit runs without ACC, making the
     * service harder for MediaTek's background power saving to kill.
     */
    private var bootWakeLock: PowerManager.WakeLock? = null

    /**
     * Runtime-registered receiver for MEDIA_BUTTON intents.
     * Unlike manifest-registered receivers, runtime receivers are NOT affected by
     * Android 8+ implicit broadcast restrictions — this is a critical difference
     * that makes steering wheel controls work on China headunits.
     */
    private val mediaButtonReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (Intent.ACTION_MEDIA_BUTTON == intent.action) {
                AppLog.i("Runtime MEDIA_BUTTON receiver fired")
                safeMediaSessionCall {
                    MediaButtonReceiver.handleIntent(it, intent)
                }
            }
        }
    }

    /**
     * Guards against duplicate [UsbAccessoryMode.connectAndSwitch] calls AND duplicate
     * [connectUsbWithRetry] calls for devices already in accessory mode.
     *
     * Set to `true` synchronously on the main thread before launching any background
     * USB connect/switch coroutine. Checked in [checkAlreadyConnectedUsb] to prevent
     * multiple concurrent connection attempts on the same device.
     * Cleared in the coroutine's finally block, or on disconnect.
     */
    private val isSwitchingToAccessory = AtomicBoolean(false)

    /**
     * Set when the phone sends VIDEO_FOCUS_NATIVE (user tapped "Exit" in AA).
     * Suppresses [scheduleReconnectIfNeeded] so we don't try to reconnect to a
     * stale dongle that hasn't re-enumerated yet.
     * Cleared on USB detach (dongle reset complete) or on fresh USB attach.
     */
    @Volatile
    private var userExitedAA = false

    private val commManager get() = App.provide(this).commManager

    fun updateMediaSessionState(isPlaying: Boolean) {
        mediaSessionIsPlaying = isPlaying
        var actions = PlaybackStateCompat.ACTION_STOP or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_PLAY_PAUSE

        var state: Int

        if (isPlaying) {
            state = PlaybackStateCompat.STATE_PLAYING
            actions = actions or PlaybackStateCompat.ACTION_PAUSE
        } else {
            state = PlaybackStateCompat.STATE_STOPPED
            actions = actions or PlaybackStateCompat.ACTION_PLAY
        }

        safeMediaSessionCall {
            it.setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setState(state, lastAaPlaybackPositionMs, if (isPlaying) 1.0f else 0.0f)
                    .setActions(actions)
                    .build()
            )
        }
        AppLog.d(
            "MediaSession: State updated to ${if (isPlaying) "PLAYING" else "STOPPED"}, positionMs=$lastAaPlaybackPositionMs"
        )
    }

    private fun applyPlaceholderMediaMetadata() {
        safeMediaSessionCall {
            it.setMetadata(
                MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, getString(R.string.video))
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, getString(R.string.media_session_aa_status_placeholder))
                    .build()
            )
        }
    }

    private fun refreshMediaSessionMetadataForPrefsChange() {
        if (isDestroying) return
        val sync = App.provide(this).settings.syncMediaSessionWithAaMetadata
        if (!sync) {
            applyPlaceholderMediaMetadata()
            cachedAaAlbumArtBitmap = null
            mediaNotification.cancel()
        } else {
            val last = lastAaMediaMetadata
            if (last != null) {
                scheduleApplyAaMediaMetadata(last)
            } else {
                applyPlaceholderMediaMetadata()
                cachedAaAlbumArtBitmap = null
                mediaNotification.cancel()
            }
        }
    }

    private fun onAaMediaMetadataFromPhone(meta: MediaPlayback.MediaMetaData) {
        if (isDestroying) return
        lastAaMediaMetadata = meta
        if (!App.provide(this).settings.syncMediaSessionWithAaMetadata) return
        // Avoid showing a previous track's art with new title/artist until decode finishes.
        cachedAaAlbumArtBitmap = null
        scheduleApplyAaMediaMetadata(meta)
    }

    private fun onAaPlaybackStatusFromPhone(status: MediaPlayback.MediaPlaybackStatus) {
        if (isDestroying) return
        if (status.hasPlaybackSeconds()) {
            lastAaPlaybackPositionMs = status.playbackSeconds.protoUint32ToLong() * 1000L
        }
        val isPlayingFromStatus = resolveIsPlayingFromStatus(status)
        lastAaPlaybackIsPlaying = isPlayingFromStatus
        mediaSessionIsPlaying = isPlayingFromStatus

        if (!App.provide(this).settings.syncMediaSessionWithAaMetadata) return
        updateMediaSessionState(isPlayingFromStatus)
        lastAaMediaMetadata?.let { updateMediaNotification(it) }
    }

    private fun resolveIsPlayingFromStatus(status: MediaPlayback.MediaPlaybackStatus): Boolean {
        if (!status.hasState()) return lastAaPlaybackIsPlaying ?: mediaSessionIsPlaying
        return when (status.state) {
            MediaPlayback.MediaPlaybackStatus.State.PLAYING -> true
            MediaPlayback.MediaPlaybackStatus.State.STOPPED,
            MediaPlayback.MediaPlaybackStatus.State.PAUSED -> false
        }
    }

    private fun updateMediaNotification(meta: MediaPlayback.MediaMetaData) {
        if (!App.provide(this).settings.syncMediaSessionWithAaMetadata) return
        mediaNotification.notify(
            metadata = meta,
            playbackSeconds = lastAaPlaybackPositionMs / 1000L,
            isPlaying = lastAaPlaybackIsPlaying ?: mediaSessionIsPlaying,
            albumArtBitmap = cachedAaAlbumArtBitmap
        )
    }

    private fun scheduleApplyAaMediaMetadata(meta: MediaPlayback.MediaMetaData) {
        mediaMetadataDecodeJob?.cancel()
        mediaMetadataDecodeJob = serviceScope.launch(Dispatchers.Default) {
            val bytes = if (meta.hasAlbumArt() && !meta.albumArt.isEmpty) meta.albumArt.toByteArray() else null
            val bitmap = bytes?.let { decodeAlbumArt(it) }
            if (!isActive) return@launch
            withContext(Dispatchers.Main) {
                if (isDestroying) return@withContext
                if (!App.provide(this@AapService).settings.syncMediaSessionWithAaMetadata) return@withContext
                // Drop stale decode results if newer metadata arrived while we were decoding.
                if (lastAaMediaMetadata !== meta) return@withContext
                cachedAaAlbumArtBitmap = bitmap
                applyAaMediaMetadataToSession(meta, bitmap)
                updateMediaNotification(meta)
            }
        }
    }

    private fun decodeAlbumArt(bytes: ByteArray): Bitmap? {
        if (bytes.isEmpty()) return null
        return try {
            val opts = BitmapFactory.Options()
            opts.inJustDecodeBounds = true
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            if (opts.outWidth <= 0 || opts.outHeight <= 0) {
                opts.inJustDecodeBounds = false
                opts.inSampleSize = 1
                return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            }
            var sampleSize = 1
            val maxDim = 720
            while (opts.outWidth / sampleSize > maxDim || opts.outHeight / sampleSize > maxDim) {
                sampleSize *= 2
            }
            opts.inJustDecodeBounds = false
            opts.inSampleSize = sampleSize
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    private fun applyAaMediaMetadataToSession(meta: MediaPlayback.MediaMetaData, albumArt: Bitmap?) {
        val session = mediaSession ?: return
        val title = when {
            meta.hasSong() && meta.song.isNotBlank() -> meta.song
            else -> getString(R.string.video)
        }
        val artist = when {
            meta.hasArtist() && meta.artist.isNotBlank() -> meta.artist
            else -> getString(R.string.media_session_aa_status_placeholder)
        }
        val b = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
        if (meta.hasAlbum() && meta.album.isNotBlank()) {
            b.putString(MediaMetadataCompat.METADATA_KEY_ALBUM, meta.album)
        }
        if (meta.hasDurationSeconds()) {
            val durationSec = meta.durationSeconds.protoUint32ToLong()
            if (durationSec > 0L) {
                b.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationSec * 1000L)
            }
        }
        if (albumArt != null) {
            b.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, albumArt)
        }
        safeMediaSessionCall { it.setMetadata(b.build()) }
    }

    // Receives ACTION_REQUEST_NIGHT_MODE_UPDATE broadcasts sent by the key-binding handler
    // when the user presses the night-mode toggle key.
    private val nightModeUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_REQUEST_NIGHT_MODE_UPDATE) {
                AppLog.i("Received request to resend night mode state")
                nightModeManager?.resendCurrentState()
            }
        }
    }

    private val sensorRefreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_REFRESH_SENSORS) {
                AppLog.i("AapService: Received request to refresh all sensors")
                // Re-send current states
                nightModeManager?.resendCurrentState()
            } else if (intent.action == ACTION_RESTART_AUDIO) {
                AppLog.i("AapService: Received request to restart audio")
                commManager.restartAudio()
            }
        }
    }

    // -------------------------------------------------------------------------
    // Wake detection for hibernate/quick boot head units
    // -------------------------------------------------------------------------

    /**
     * Timestamp (elapsedRealtime) when the screen last turned off.
     * Used to measure how long the device was asleep and distinguish a normal
     * screen timeout from a hibernate wake (car ACC off → on).
     */
    private var screenOffTimestamp = 0L

    /**
     * Debounce: last time [onHibernateWake] actually ran.
     * Prevents double-triggering when both BootCompleteReceiver and this dynamic
     * receiver fire for the same wake event.
     */
    private var lastWakeHandledTimestamp = 0L

    /**
     * Runtime-registered receiver for system wake/boot/power/screen events.
     *
     * On Chinese head units with Quick Boot (hibernate/resume), standard broadcasts
     * like BOOT_COMPLETED and USB_DEVICE_ATTACHED often don't fire after waking.
     * This receiver serves two purposes:
     *
     * 1. **Diagnostic logging:** Logs every received system event with the
     *    "WakeDetect:" prefix so users can export logs and we can see which
     *    broadcasts their specific head unit sends (or doesn't send) on wake.
     *
     * 2. **Universal wake detection:** Uses ACTION_SCREEN_ON (which fires on ALL
     *    devices after hibernate) combined with screen-off duration tracking to
     *    detect hibernate wakes and trigger auto-start — regardless of which OEM
     *    boot/ACC intents the device sends.
     *
     * ACTION_SCREEN_ON can only be received by dynamically registered receivers,
     * not manifest-declared ones — that's why the manifest-based BootCompleteReceiver
     * can't catch it and we need this service-based approach.
     */
    private val wakeDetectReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action ?: return

            when (action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenOffTimestamp = SystemClock.elapsedRealtime()
                    AppLog.i("WakeDetect: SCREEN_OFF")
                }
                Intent.ACTION_SCREEN_ON -> {
                    val now = SystemClock.elapsedRealtime()
                    val offDuration = if (screenOffTimestamp > 0) now - screenOffTimestamp else -1L
                    val offSec = if (offDuration >= 0) offDuration / 1000 else -1L
                    screenOffTimestamp = 0

                    AppLog.i("WakeDetect: SCREEN_ON (screen was off for ${offSec}s)")

                    val settings = App.provide(this@AapService).settings

                    // "Start on screen on" — triggers on every SCREEN_ON, designed for
                    // head units that never truly power off (quick boot / always-on).
                    if (settings.autoStartOnScreenOn) {
                        AppLog.i("WakeDetect: start-on-screen-on enabled, triggering auto-start")
                        onScreenOnAutoStart()
                    } else if (offDuration > HIBERNATE_WAKE_THRESHOLD_MS) {
                        // Hibernate wake detection — only for longer sleeps
                        AppLog.i("WakeDetect: hibernate wake detected (off for ${offSec}s > ${HIBERNATE_WAKE_THRESHOLD_MS / 1000}s threshold)")
                        onHibernateWake("SCREEN_ON after ${offSec}s sleep")
                    }
                }
                Intent.ACTION_USER_PRESENT -> {
                    AppLog.i("WakeDetect: USER_PRESENT")
                }
                Intent.ACTION_POWER_CONNECTED -> {
                    AppLog.i("WakeDetect: POWER_CONNECTED")
                    // On some head units, power connected = ACC on = car started.
                    // Only check USB (don't launch UI) since this could also be a
                    // charger being plugged in on a phone/tablet.
                    onPossibleWake("POWER_CONNECTED")
                }
                Intent.ACTION_POWER_DISCONNECTED -> {
                    AppLog.i("WakeDetect: POWER_DISCONNECTED")
                }
                Intent.ACTION_SHUTDOWN -> {
                    AppLog.i("WakeDetect: SHUTDOWN (system shutting down, not hibernating)")
                }
                else -> {
                    // OEM boot/ACC/wake intents — log with extras for diagnostics
                    AppLog.i("WakeDetect: $action")
                    val extras = intent.extras
                    if (extras != null && !extras.isEmpty) {
                        val extrasStr = extras.keySet().joinToString { "$it=${extras.get(it)}" }
                        AppLog.i("WakeDetect: extras: $extrasStr")
                    }
                    // Any OEM boot/ACC intent received dynamically = definite wake
                    onHibernateWake(action)
                }
            }
        }
    }

    /**
     * Called when we've confidently detected a hibernate wake (screen was off for
     * a long time, or an OEM boot/ACC intent was received by the dynamic receiver).
     */
    private fun onHibernateWake(trigger: String) {
        // Debounce: don't re-trigger within 10 seconds (covers BootCompleteReceiver + this)
        val now = SystemClock.elapsedRealtime()
        if (now - lastWakeHandledTimestamp < 10_000) {
            AppLog.i("WakeDetect: wake already handled ${(now - lastWakeHandledTimestamp) / 1000}s ago, skipping ($trigger)")
            return
        }
        lastWakeHandledTimestamp = now

        if (commManager.isConnected ||
            commManager.connectionState.value is CommManager.ConnectionState.Connecting ||
            isSwitchingToAccessory.get()) {
            AppLog.i("WakeDetect: already connected/connecting, skipping ($trigger)")
            return
        }

        val settings = App.provide(this).settings

        if (settings.autoStartOnBoot) {
            AppLog.i("WakeDetect: launching UI (trigger=$trigger)")
            launchMainActivityOnBoot()
        }

        if (settings.autoStartOnUsb) {
            AppLog.i("WakeDetect: checking USB devices (trigger=$trigger)")
            checkAlreadyConnectedUsb(force = true)
        }
    }

    /**
     * Called on events that MIGHT indicate a wake (e.g. POWER_CONNECTED) but aren't
     * conclusive alone. Only checks USB — does not launch the UI.
     */
    private fun onPossibleWake(trigger: String) {
        if (commManager.isConnected ||
            commManager.connectionState.value is CommManager.ConnectionState.Connecting ||
            isSwitchingToAccessory.get()) return

        val settings = App.provide(this).settings
        if (settings.autoStartOnUsb) {
            AppLog.i("WakeDetect: possible wake, checking USB (trigger=$trigger)")
            checkAlreadyConnectedUsb(force = true)
        }
    }

    /**
     * Called on every SCREEN_ON when "Start on screen on" is enabled.
     * Designed for head units that never truly power off — screen on = car turned on.
     *
     * If the connection is still active (e.g. brief screen toggle), returns to the
     * projection activity. Otherwise launches the main UI and checks USB.
     */
    private fun onScreenOnAutoStart() {
        // Debounce: don't re-trigger within 5 seconds
        val now = SystemClock.elapsedRealtime()
        if (now - lastWakeHandledTimestamp < 5_000) {
            AppLog.i("WakeDetect: screen-on auto-start already handled recently, skipping")
            return
        }
        lastWakeHandledTimestamp = now

        // Acquire wake lock to resist power saving cleanup on Quick Boot devices
        acquireBootWakeLock()

        if (commManager.isConnected) {
            // Connection still alive — return to projection screen
            if (App.isPiPActive) {
                AppLog.i("WakeDetect: connection active, but PiP is active. Skipping return to full screen.")
                return
            }
            AppLog.i("WakeDetect: connection active, returning to projection")
            try {
                val projectionIntent = AapProjectionActivity.intent(this).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                }
                startActivity(projectionIntent)
            } catch (e: Exception) {
                AppLog.e("WakeDetect: failed to launch projection: ${e.message}")
            }
            return
        }

        if (commManager.connectionState.value is CommManager.ConnectionState.Connecting ||
            isSwitchingToAccessory.get()) {
            AppLog.i("WakeDetect: already connecting, skipping screen-on auto-start")
            return
        }

        // Not connected — launch UI (which triggers auto-connect via HomeFragment)
        AppLog.i("WakeDetect: launching UI on screen on")
        launchMainActivityOnBoot()

        val settings = App.provide(this).settings
        if (settings.autoStartOnUsb) {
            AppLog.i("WakeDetect: checking USB devices on screen on")
            checkAlreadyConnectedUsb(force = true)
        }
    }

    override fun onBind(intent: Intent): IBinder? = null

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        AppLog.i("AapService creating...")

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(1, createNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(1, createNotification())
            }
        } catch (e: Exception) {
            AppLog.e("ForegroundServiceStartNotAllowedException/Exception caught in onCreate: ${e.message}", e)
            stopSelf()
            return
        }
        setupCarMode()
        setupNightMode()
        observeConnectionState()
        registerReceivers()

        // Initialize MediaSession early and set it active immediately.
        // This ensures media button routing works even BEFORE an AA connection,
        // which is critical for keymap configuration and early button presses.
        if (mediaSession == null) {
            setupMediaSession()
        }
        safeMediaSessionCall { it.isActive = true }
        updateMediaSessionState(false) // Set initial PlaybackState so system knows our actions

        commManager.onAaMediaMetadata = { meta -> onAaMediaMetadataFromPhone(meta) }
        commManager.onAaPlaybackStatus = { status -> onAaPlaybackStatusFromPhone(status) }
        settingsPrefs = getSharedPreferences("settings", MODE_PRIVATE).also { prefs ->
            prefs.registerOnSharedPreferenceChangeListener(settingsPreferenceListener)
        }

        AppLog.init(settings, this)
        syncLogBackendState()

        AppLog.i("AapService: starting USB-only service path.")

        checkAlreadyConnectedUsb()
    }

    /** Enables Android Automotive UI mode so the system uses car-optimised layouts. */
    private fun setupCarMode() {
        try {
            val mgr = getSystemService(UI_MODE_SERVICE) as? UiModeManager
            if (mgr != null) {
                uiModeManager = mgr
                mgr.enableCarMode(0)
            }
        } catch (e: Exception) {
            AppLog.w("AapService: Failed to enable car mode: ${e.message}")
        }
    }

    /** Initialises [NightModeManager] and forwards night-mode changes to Android Auto via AAP. */
    private fun setupNightMode() {
        nightModeManager = NightModeManager(this, App.provide(this).settings) { isNight ->
            AppLog.i("NightMode update: $isNight")
            commManager.send(NightModeEvent(isNight))
            // Also notify local components (for AA monochrome filter)
            val intent = Intent(ACTION_NIGHT_MODE_CHANGED).apply {
                setPackage(packageName)
                putExtra("isNight", isNight)
            }
            sendBroadcast(intent)
        }
    }

    /**
     * Single observer for all [CommManager.ConnectionState] transitions.
     *
     * Uses [hasEverConnected] to skip the initial [ConnectionState.Disconnected] emission
     * from StateFlow replay, avoiding a spurious disconnect on startup.
     */
    private fun observeConnectionState() {
        serviceScope.launch {
            commManager.connectionState.collect { state ->
                when (state) {
                    is CommManager.ConnectionState.Connected -> onConnected()
                    is CommManager.ConnectionState.HandshakeComplete -> {
                        launchAapProjectionActivity()
                    }
                    is CommManager.ConnectionState.TransportStarted -> {
                        hasEverConnected = true
                        accessoryHandshakeFailures = 0
                        sendBroadcast(Intent(ACTION_REQUEST_NIGHT_MODE_UPDATE).apply {
                            setPackage(packageName)
                        })
                    }
                    is CommManager.ConnectionState.Error -> {
                        if (state.message.contains("Handshake failed")) {
                            onHandshakeFailed()
                        }
                    }
                    is CommManager.ConnectionState.Disconnected -> {
                        if (hasEverConnected) onDisconnected(state)
                    }
                    else -> {}
                }
            }
        }
    }

    /**
     * Performs the permanent audio focus request used for AA audio sink.
     *
     * This logic was previously executed in onCreate(); it has been moved here so
     * the caller can decide when to acquire focus (for example, immediately before
     * starting the AA handshake) to avoid stealing audio during autostart.
     *
     * The permanent AUDIOFOCUS_GAIN is only appropriate for Static Audio Focus mode,
     * where the phone must believe focus is always held. In the default (dynamic) mode
     * focus is instead acquired on demand via the AA protocol
     * (AapControl.audioFocusRequest -> AapAudio.requestFocusChange), so grabbing a
     * permanent gain here would needlessly evict other media (e.g. the car radio) the
     * moment the phone connects, before AA plays anything.
     */
    private fun requestPermanentAudioFocus() {
        if (!settings.enableAudioSink) {
            AppLog.d("Audio Sink disabled - skipping permanent audio focus request.")
            return
        }
        if (!settings.staticAudioFocus) {
            AppLog.d("Static Audio Focus disabled - skipping permanent audio focus request; focus will be acquired on demand.")
            return
        }

        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (permanentFocusRequest == null) {
                    val attrs = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                    permanentFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(attrs)
                        .setWillPauseWhenDucked(false)
                        .setOnAudioFocusChangeListener { focusChange ->
                            AppLog.d("AapService: Permanent audio focus changed: $focusChange")
                        }
                        .build()
                }
                val res = audioManager.requestAudioFocus(permanentFocusRequest!!)
                AppLog.d("AapService: requestPermanentAudioFocus: result=$res")
            } else {
                @Suppress("DEPRECATION")
                val res = audioManager.requestAudioFocus(
                    { focusChange -> AppLog.d("AapService: Permanent audio focus changed: $focusChange") },
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN
                )
                AppLog.d("AapService: requestPermanentAudioFocus (legacy): result=$res")
            }
        } catch (e: Exception) {
            AppLog.e("AapService: requestPermanentAudioFocus failed", e)
        }
    }

    /**
     * Releases any permanent audio focus previously requested by [requestPermanentAudioFocus].
     *
     * This is invoked on disconnect to return audio focus to the phone or other media
     * apps so that playback can resume normally. Supports both the modern
     * AudioFocusRequest API (API >= O) and the legacy abandonAudioFocus path.
     */
    private fun releasePermanentAudioFocus() {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                permanentFocusRequest?.let {
                    audioManager.abandonAudioFocusRequest(it)
                    AppLog.d("AapService: abandoned permanent audio focus request")
                    permanentFocusRequest = null
                }
            } else {
                @Suppress("DEPRECATION")
                try {
                    audioManager.abandonAudioFocus(null)
                    AppLog.d("AapService: abandoned legacy audio focus (null listener)")
                } catch (e: Exception) {
                    // Some devices may not accept a null listener; ignore failures
                    AppLog.e("AapService: releasePermanentAudioFocus failed", e)
                }
            }
        } catch (e: Exception) {
            AppLog.e("AapService: Failed to abandon audio focus", e)
        }
    }

    /**
     * Called by [CommManager.ConnectionState.Connected] observer:
     * 1. Refreshes the foreground notification.
     * 2. Activates a [MediaSessionCompat] so media keys are routed to Android Auto.
     * 3. Starts the SSL handshake ([CommManager.startHandshake]) **in parallel** with
     *    launching [AapProjectionActivity], hiding multi-second handshake latency behind
     *    activity-inflation time.
     *
     * The inbound message loop ([CommManager.startReading]) is intentionally NOT started
     * here. It is deferred until [AapProjectionActivity] confirms its render surface is
     * ready (via [CommManager.ConnectionState.HandshakeComplete] observer), guaranteeing
     * that [VideoDecoder.setSurface] is always called before the first video frame arrives.
     */
    private fun onConnected() {
        isSwitchingToAccessory.set(false)
        updateNotification()

        // Silent audio hack removed to prevent mixing/resampling stuttering issues

        // Register the comprehensive steering wheel key receiver
        App.provide(this).carKeysManager.registerReceivers(this)

        // Reactivate the existing MediaSession (created in onCreate, kept alive across disconnects)
        safeMediaSessionCall { it.isActive = true }
        updateMediaSessionState(true)
        applyPlaceholderMediaMetadata()

        // Link audio focus state changes to our MediaSession state
        commManager.onAudioFocusStateChanged = { isPlaying ->
            updateMediaSessionState(isPlaying)
        }

        // Acquire permanent audio focus just before starting the AA handshake so we
        // don't steal audio during service autostart but still obtain focus when a
        // real connection is beginning.
        requestPermanentAudioFocus()

        // Start GpsLocationService and NightModeManager sensor tracking
        AppLog.i("AapService: Starting GpsLocationService and NightModeManager since connection is established")
        startService(GpsLocationService.intent(this))
        nightModeManager?.start()

        serviceScope.launch { commManager.startHandshake() }
    }

    private fun launchAapProjectionActivity() {
        if (App.isPiPActive) {
            AppLog.i("AapService: Skipping projection launch because PiP is active")
            return
        }

        val intent = AapProjectionActivity.intent(this).apply {
            putExtra(AapProjectionActivity.EXTRA_FOCUS, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }

        val canOverlay = AppPermissions.isOverlayGranted(this)
        when (ActivityLaunchPolicy.chooseLaunchStrategy(Build.VERSION.SDK_INT, canOverlay)) {
            ActivityLaunchPolicy.LaunchStrategy.DIRECT -> {
                try { startActivity(intent) }
                catch (e: Exception) { AppLog.e("Projection launch failed: ${e.message}") }
            }
            ActivityLaunchPolicy.LaunchStrategy.OVERLAY -> {
                if (!launchViaOverlayTrampoline(intent)) {
                    AppLog.w("Projection overlay trampoline failed, trying direct")
                    try { startActivity(intent) }
                    catch (e: Exception) { AppLog.e("Projection direct fallback failed: ${e.message}") }
                }
            }
            ActivityLaunchPolicy.LaunchStrategy.NOTIFICATION -> launchProjectionViaNotification(intent)
        }
    }

    private fun launchProjectionViaNotification(launchIntent: Intent) {
        val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        val fullScreenPi = PendingIntent.getActivity(this, PROJECTION_LAUNCH_NOTIFICATION_ID, launchIntent, piFlags)

        val notification = NotificationCompat.Builder(this, App.bootStartChannel)
            .setSmallIcon(R.drawable.ic_stat_aa)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.android_auto_starting))
            .setFullScreenIntent(fullScreenPi, true)
            .setContentIntent(fullScreenPi)
            .setAutoCancel(true)
            .build()

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(PROJECTION_LAUNCH_NOTIFICATION_ID, notification)
        serviceScope.launch {
            delay(5000)
            nm.cancel(PROJECTION_LAUNCH_NOTIFICATION_ID)
        }
    }

    private fun setupMediaSession() {
        val mbr = ComponentName(this, MediaButtonReceiver::class.java)
        mediaSession = MediaSessionCompat(this, "HeadunitRevived", mbr, null).apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onMediaButtonEvent(mediaButtonEvent: Intent?): Boolean {
                    val keyEvent = mediaButtonEvent?.let { IntentCompat.getParcelableExtra(it, Intent.EXTRA_KEY_EVENT, android.view.KeyEvent::class.java) }

                    if (keyEvent != null) {
                        val actionStr = if (keyEvent.action == android.view.KeyEvent.ACTION_DOWN) "DOWN" else "UP"
                        AppLog.d("MediaButtonEvent: Received key ${keyEvent.keyCode} ($actionStr)")

                        // Only handle ACTION_DOWN to prevent double triggers from standard Android behavior.
                        // Physical double triggers are handled by CommManager.sendKey deduplication.
                        if (keyEvent.action == android.view.KeyEvent.ACTION_DOWN && keyEvent.repeatCount == 0) {
                            AppLog.i("MediaButtonEvent: Processing key ${keyEvent.keyCode}")
                            // Send a complete click sequence (press + release) immediately
                            commManager.sendKey(keyEvent.keyCode, true)
                            commManager.sendKey(keyEvent.keyCode, false)
                            return true
                        }

                        // Consume ACTION_UP to prevent fallback
                        if (keyEvent.action == android.view.KeyEvent.ACTION_UP) {
                            return true
                        }
                    }

                    return super.onMediaButtonEvent(mediaButtonEvent)
                }

                override fun onPause() {
                    AppLog.i("MediaSession: Processing transport control action = KEYCODE_MEDIA_PAUSE")
                    commManager.sendKey(android.view.KeyEvent.KEYCODE_MEDIA_PAUSE, true)
                    commManager.sendKey(android.view.KeyEvent.KEYCODE_MEDIA_PAUSE, false)
                }

                override fun onPlay() {
                    AppLog.i("MediaSession: Processing transport control action = KEYCODE_MEDIA_PLAY")
                    commManager.sendKey(android.view.KeyEvent.KEYCODE_MEDIA_PLAY, true)
                    commManager.sendKey(android.view.KeyEvent.KEYCODE_MEDIA_PLAY, false)
                }

                override fun onSkipToNext() {
                    AppLog.i("MediaSession: Processing transport control action = KEYCODE_MEDIA_NEXT")
                    commManager.sendKey(android.view.KeyEvent.KEYCODE_MEDIA_NEXT, true)
                    commManager.sendKey(android.view.KeyEvent.KEYCODE_MEDIA_NEXT, false)
                }

                override fun onSkipToPrevious() {
                    AppLog.i("MediaSession: Processing transport control action = KEYCODE_MEDIA_PREVIOUS")
                    commManager.sendKey(android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS, true)
                    commManager.sendKey(android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS, false)
                }

                override fun onStop() {
                    AppLog.i("MediaSession: Processing transport control action = KEYCODE_MEDIA_STOP")
                    commManager.sendKey(android.view.KeyEvent.KEYCODE_MEDIA_STOP, true)
                    commManager.sendKey(android.view.KeyEvent.KEYCODE_MEDIA_STOP, false)
                }
            })
            setPlaybackToLocal(android.media.AudioManager.STREAM_MUSIC)
        }
        applyPlaceholderMediaMetadata()
    }

    /**
     * Called by [CommManager.ConnectionState.Disconnected] observer:
     * 1. Refreshing the notification (unless we are already tearing down)
     * 2. Releasing the [MediaSessionCompat]
     * 3. Stopping audio/video decoders on the IO thread
     * 4. Scheduling a reconnect attempt if applicable (see [scheduleReconnectIfNeeded])
     */
    private fun onDisconnected(state: CommManager.ConnectionState.Disconnected) {
        isSwitchingToAccessory.set(false)

        AppLog.i("AapService: Stopping GpsLocationService and NightModeManager since connection is disconnected")
        stopService(GpsLocationService.intent(this))
        nightModeManager?.stop()

        releasePermanentAudioFocus()
        App.provide(this).carKeysManager.unregisterReceivers()

        if (!isDestroying) updateNotification()
        mediaMetadataDecodeJob?.cancel()
        mediaMetadataDecodeJob = null
        lastAaMediaMetadata = null
        lastAaPlaybackPositionMs = 0L
        lastAaPlaybackIsPlaying = null
        cachedAaAlbumArtBitmap = null
        mediaNotification.cancel()
        applyPlaceholderMediaMetadata()
        safeMediaSessionCall { it.isActive = false }
        updateMediaSessionState(false)
        serviceScope.launch(Dispatchers.IO) {
            App.provide(this@AapService).audioDecoder.stop()
            App.provide(this@AapService).videoDecoder.stop("AapService::onDisconnect")
        }

        scheduleReconnectIfNeeded(state)
    }

    /**
     * Schedules a reconnect attempt after an unexpected USB disconnect.
     */
    private fun scheduleReconnectIfNeeded(state: CommManager.ConnectionState.Disconnected) {
        val settings = App.provide(this).settings
        val lastType = settings.lastConnectionType

        if (lastType == Settings.CONNECTION_TYPE_USB &&
            (settings.autoConnectLastSession || settings.autoConnectSingleUsbDevice)) {
            if (state.isUserExit && !(settings.autoStartOnUsb && settings.reopenOnReconnection)) {
                AppLog.i("AapService: USB disconnect after user Exit. Skipping auto-reconnect (waiting for dongle re-enumeration).")
                userExitedAA = true
                return
            }
            if (state.isUserExit && settings.autoStartOnUsb && settings.reopenOnReconnection) {
                AppLog.i("AapService: USB disconnect after user Exit with reopenOnReconnection enabled. Will reconnect on next USB attach.")
                return
            }
            AppLog.i("AapService: USB disconnect. Scheduling reconnect check in ${USB_RECONNECT_DELAY_MS}ms...")
            serviceScope.launch {
                delay(USB_RECONNECT_DELAY_MS)
                if (!commManager.isConnected) checkAlreadyConnectedUsb(force = true)
            }
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase))
    }

    private fun registerReceivers() {
        usbReceiver = UsbReceiver(this)
        ContextCompat.registerReceiver(
            this, nightModeUpdateReceiver,
            IntentFilter(ACTION_REQUEST_NIGHT_MODE_UPDATE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ContextCompat.registerReceiver(
            this, sensorRefreshReceiver,
            IntentFilter(ACTION_REFRESH_SENSORS).apply { addAction(ACTION_RESTART_AUDIO) },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ContextCompat.registerReceiver(
            this, usbReceiver,
            UsbReceiver.createFilter(),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        // Runtime-registered MEDIA_BUTTON receiver.
        // Unlike manifest-registered receivers, runtime receivers bypass the
        // Android 8+ implicit broadcast restriction. This is the primary mechanism
        // that makes steering wheel media buttons work on China headunits.
        ContextCompat.registerReceiver(
            this, mediaButtonReceiver,
            IntentFilter(Intent.ACTION_MEDIA_BUTTON),
            ContextCompat.RECEIVER_EXPORTED
        )
        AppLog.i("Registered runtime MEDIA_BUTTON receiver")

        // Wake detection receiver: catches SCREEN_ON, SCREEN_OFF, POWER_CONNECTED,
        // and all known OEM boot/ACC intents. Enables hibernate wake detection on
        // Quick Boot head units where BOOT_COMPLETED never fires.
        val wakeFilter = IntentFilter().apply {
            // Screen events (only receivable by dynamic receivers on Android 8+)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
            // Power events
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_SHUTDOWN)
            // Standard boot (dynamic duplicate — BootCompleteReceiver handles manifest side)
            addAction(Intent.ACTION_BOOT_COMPLETED)
            addAction(Intent.ACTION_LOCKED_BOOT_COMPLETED)
            // Quick boot variants
            addAction("android.intent.action.QUICKBOOT_POWERON")
            addAction("com.htc.intent.action.QUICKBOOT_POWERON")
            // MediaTek IPO (Instant Power On)
            addAction("com.mediatek.intent.action.QUICKBOOT_POWERON")
            addAction("com.mediatek.intent.action.BOOT_IPO")
            // FYT / GLSX head units (ACC ignition wake)
            addAction("com.fyt.boot.ACCON")
            addAction("com.glsx.boot.ACCON")
            addAction("android.intent.action.ACTION_MT_COMMAND_SLEEP_OUT")
            // Microntek / MTCD / PX3 head units (ACC wake)
            addAction("com.cayboy.action.ACC_ON")
            addAction("com.carboy.action.ACC_ON")
        }
        ContextCompat.registerReceiver(
            this, wakeDetectReceiver,
            wakeFilter,
            ContextCompat.RECEIVER_EXPORTED
        )
        AppLog.i("Registered wake detection receiver (${wakeFilter.countActions()} actions)")
    }

    /**
     * Acquires a partial wake lock to resist MediaTek/Reglink background power
     * saving that force-stops third-party apps when ACC is off.
     * The wake lock has a 10-minute timeout as a safety net.
     */
    private fun acquireBootWakeLock() {
        if (bootWakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        bootWakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "HeadunitRevived::BootAutoStart"
        ).apply {
            acquire(10 * 60 * 1000L) // 10 minute timeout
        }
        AppLog.i("Boot WakeLock acquired (10min timeout)")

        // Log battery optimization status for diagnostics
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val exempt = pm.isIgnoringBatteryOptimizations(packageName)
            AppLog.i("Battery optimization exempt: $exempt")
        }
    }

    private fun releaseBootWakeLock() {
        if (bootWakeLock?.isHeld == true) {
            bootWakeLock?.release()
            AppLog.i("Boot WakeLock released")
        }
        bootWakeLock = null
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        AppLog.i("AapService: onTaskRemoved — attempting restart")
        try {
            val restartIntent = Intent(this, AapService::class.java)
            ContextCompat.startForegroundService(this, restartIntent)
        } catch (e: Exception) {
            AppLog.e("AapService: failed to restart after task removal: ${e.message}")
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        AppLog.i("AapService destroying... (wakeLock held=${bootWakeLock?.isHeld == true})")
        isDestroying = true
        mediaMetadataDecodeJob?.cancel()
        cachedAaAlbumArtBitmap = null
        mediaNotification.cancel()
        commManager.onAaMediaMetadata = null
        commManager.onAaPlaybackStatus = null
        settingsPrefs?.unregisterOnSharedPreferenceChangeListener(settingsPreferenceListener)
        settingsPrefs = null
        releaseBootWakeLock()

        stopForeground(true)
        try {
            mediaSession?.let {
                it.isActive = false
                it.release()
            }
        } catch (e: Exception) {
            AppLog.e("Error releasing MediaSession: ${e.message}")
        }
        mediaSession = null
        commManager.destroy()
        nightModeManager?.stop()
        stopService(GpsLocationService.intent(this))
        try {
            unregisterReceiver(nightModeUpdateReceiver)
            unregisterReceiver(sensorRefreshReceiver)
        } catch (_: Exception) {}
        try { unregisterReceiver(usbReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(mediaButtonReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(wakeDetectReceiver) } catch (_: Exception) {}
        try { App.provide(this).carKeysManager.unregisterReceivers() } catch (e: Exception) { AppLog.w("AapService: Error unregistering carKeysManager: ${e.message}") }
        try {
            if (::uiModeManager.isInitialized) {
                uiModeManager.disableCarMode(0)
            }
        } catch (e: Exception) {
            AppLog.w("AapService: Error disabling car mode: ${e.message}")
        }
        try { serviceScope.cancel() } catch (_: Exception) {}
        try { LogExporter.stopCapture() } catch (_: Exception) {}
        super.onDestroy()
        if (killProcessOnDestroy) {
            AppLog.i("AapService: killProcessOnDestroy is true. Triggering System.exit(0).")
            System.exit(0)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(1, createNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(1, createNotification())
            }
        } catch (e: Exception) {
            AppLog.e("ForegroundServiceStartNotAllowedException/Exception caught in onStartCommand: ${e.message}", e)
            stopSelf()
            return START_NOT_STICKY
        }

        // Handle stop before re-posting the notification to avoid a flash
        if (intent?.action == ACTION_STOP_SERVICE) {
            AppLog.i("Stop action received. Broadcasting finish request to activities.")
            sendBroadcast(Intent("com.skystream.ssheadunit.ACTION_FINISH_ACTIVITIES").apply {
                setPackage(packageName)
            })
            isDestroying = true
            if (commManager.isConnected) commManager.disconnect(sendByeBye = true)
            stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }

        // Route MEDIA_BUTTON intents to the active MediaSession.
        safeMediaSessionCall { MediaButtonReceiver.handleIntent(it, intent) }
        // Launch the UI after boot.
        // Direct startActivity() is silently blocked on MIUI/HyperOS even from
        // a foreground service. We use an overlay window trampoline: creating a
        // zero-size overlay gives the app a "visible" context that bypasses OEM
        // background activity start restrictions. Falls back to full-screen
        // intent notification if overlay permission is not granted.
        // Acquire a partial wake lock on any boot/screen-on start to resist
        // aggressive power saving on MediaTek/Reglink head units that force-stop
        // third-party apps when ACC is off after a Quick Boot reboot.
        if (intent?.getBooleanExtra(BootCompleteReceiver.EXTRA_BOOT_START, false) == true ||
            intent?.action == ACTION_CHECK_USB) {
            acquireBootWakeLock()
        }

        if (intent?.getBooleanExtra(BootCompleteReceiver.EXTRA_BOOT_START, false) == true) {
            // Mark wake as handled so the dynamic wakeDetectReceiver doesn't double-trigger
            lastWakeHandledTimestamp = SystemClock.elapsedRealtime()
            launchMainActivityOnBoot()
        }

        when (intent?.action) {
            ACTION_DISCONNECT            -> {
                AppLog.i("Disconnect action received.")
                // disconnect() has its own early-return when already Disconnected,
                // and unlike the previous isConnected guard it also covers the
                // Connecting state, so the UI cancel paths work before handshake
                // completes.
                commManager.disconnect()
            }
            ACTION_CHECK_USB             -> checkAlreadyConnectedUsb(force = true)
            else                         -> {
                if (intent?.action == null || intent.action == Intent.ACTION_MAIN) {
                    checkAlreadyConnectedUsb()
                }
            }
        }
        return START_STICKY
    }

    // -------------------------------------------------------------------------
    // USB
    // -------------------------------------------------------------------------

    override fun onUsbAttach(device: UsbDevice) {
        if (!UsbDeviceCompat.isAndroidDevice(device)) {
            AppLog.i("Ignoring non-Android USB device attached in service (VID: ${device.vendorId}): ${device.deviceName}")
            return
        }
        userExitedAA = false
        if (UsbDeviceCompat.isInAccessoryMode(device)) {
            // Device already in AOA mode (re-enumerated after UsbAttachedActivity switched it).
            AppLog.i("USB accessory device attached, connecting.")
            launchMainActivityIfNeeded("USB accessory attach")
            checkAlreadyConnectedUsb(force = true)
        } else {
            // UsbAttachedActivity normally handles normal-mode devices via a manifest intent
            // filter. However, some headunits (especially Chinese MediaTek units) don't
            // deliver USB_DEVICE_ATTACHED to activities on cold start. As a fallback,
            // check after a delay to give UsbAttachedActivity a chance to handle it first.
            val deviceName = UsbDeviceCompat(device).uniqueName
            AppLog.i("Normal USB device attached: $deviceName. Will check auto-connect in ${USB_ATTACH_FALLBACK_DELAY_MS}ms...")
            launchMainActivityIfNeeded("USB normal attach ($deviceName)")
            serviceScope.launch {
                delay(USB_ATTACH_FALLBACK_DELAY_MS)
                if (!commManager.isConnected && !isSwitchingToAccessory.get()) {
                    AppLog.i("UsbAttachedActivity didn't handle $deviceName. Trying from service...")
                    checkAlreadyConnectedUsb(force = true)
                }
            }
        }
    }

    override fun onUsbDetach(device: UsbDevice) {
        userExitedAA = false
        if (commManager.isConnectedToUsbDevice(device)) {
            // Cable physically removed — the USB connection is already dead, so skip the
            // ByeByeRequest send (which would block ~1 s trying to write to a gone device).
            commManager.disconnect(sendByeBye = false, isUserExit = false)
        }
    }

    override fun onUsbAccessoryDetach() {
        AppLog.i("USB Accessory detached. This might be a transient state (e.g., 100% battery). Attempting to re-sync...")
        userExitedAA = false
        if (commManager.isConnected) {
            commManager.disconnect(sendByeBye = false, isUserExit = false)
        }

        // Wait a bit and check if the device is still there in normal mode
        serviceScope.launch {
            delay(1500) // Give the phone/system time to settle its USB state
            AppLog.i("Accessory detach cooldown finished. Checking for re-connection...")
            checkAlreadyConnectedUsb(force = true)
        }
    }

    override fun onUsbPermission(granted: Boolean, connect: Boolean, device: UsbDevice) {
        if (!UsbDeviceCompat.isAndroidDevice(device)) {
            AppLog.i("Ignoring USB permission callback for non-Android device (VID: ${device.vendorId}): ${device.deviceName}")
            return
        }
        val deviceName = UsbDeviceCompat(device).uniqueName
        if (granted) {
            AppLog.i("USB permission granted for $deviceName")
            if (UsbDeviceCompat.isInAccessoryMode(device)) {
                isSwitchingToAccessory.set(true)
                serviceScope.launch {
                    try {
                        connectUsbWithRetry(device)
                    } finally {
                        isSwitchingToAccessory.set(false)
                    }
                }
            } else {
                isSwitchingToAccessory.set(true)
                val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
                val settings = App.provide(this).settings
                val usbMode = UsbAccessoryMode(usbManager)
                serviceScope.launch(Dispatchers.IO) {
                    try {
                        if (usbMode.connectAndSwitch(device, settings.useLibusb)) {
                            AppLog.i("Successfully requested switch to accessory mode for $deviceName")
                        } else {
                            AppLog.w("USB permission granted but connectAndSwitch failed for $deviceName")
                        }
                    } finally {
                        isSwitchingToAccessory.set(false)
                    }
                }
            }
        } else {
            AppLog.w("USB permission denied for $deviceName")
            ToastUtils.showToast(this, getString(R.string.usb_permission_denied), Toast.LENGTH_LONG)
        }
    }

    private fun requestUsbPermission(device: UsbDevice) {
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val permissionIntent = UsbReceiver.createPermissionPendingIntent(this)
        AppLog.i("Requesting USB permission for ${UsbDeviceCompat(device).uniqueName}")
        try {
            ToastUtils.showToast(this, getString(R.string.requesting_usb_permission), Toast.LENGTH_SHORT)
            usbManager.requestPermission(device, permissionIntent)
        } catch (e: Exception) {
            AppLog.e("Failed to request USB permission: ${e.message}. This device might not support USB permission dialogs.", e)
            ToastUtils.showToast(this, getString(R.string.error_usb_permission_failed), Toast.LENGTH_LONG)
        }
    }

    /**
     * Called when a handshake fails. If an accessory-mode device is still present,
     * it's likely a stale USB AA dongle. Force re-enumeration by sending AOA
     * descriptors — this resets the dongle's USB state so the next connection
     * starts with clean buffers.
     */
    private fun onHandshakeFailed() {
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val accessoryDevice = usbManager.deviceList.values.firstOrNull {
            UsbDeviceCompat.isInAccessoryMode(it)
        } ?: return

        accessoryHandshakeFailures++
        val deviceName = UsbDeviceCompat(accessoryDevice).uniqueName
        AppLog.w("Handshake failed on accessory device $deviceName (failure #$accessoryHandshakeFailures)")

        if (accessoryHandshakeFailures > MAX_STALE_ACCESSORY_RETRIES) {
            AppLog.i("Stale accessory detected: forcing re-enumeration via AOA descriptors for $deviceName")
            accessoryHandshakeFailures = 0
            val settings = App.provide(this).settings
            val usbMode = UsbAccessoryMode(usbManager)
            isSwitchingToAccessory.set(true)
            serviceScope.launch(Dispatchers.IO) {
                try {
                    if (usbMode.connectAndSwitch(accessoryDevice, settings.useLibusb)) {
                        AppLog.i("AOA re-enumeration requested for stale device $deviceName")
                    } else {
                        AppLog.w("AOA re-enumeration failed for $deviceName")
                    }
                } catch (e: Exception) {
                    AppLog.e("AOA re-enumeration for $deviceName failed with exception", e)
                } finally {
                    isSwitchingToAccessory.set(false)
                }
            }
        }
    }

    /**
     * Scans currently connected USB devices and connects to any that are already in
     * Android Open Accessory (AOA) mode, or attempts to switch a known device into AOA mode.
     *
     * @param force When `true`, bypasses the [autoConnectLastSession] guard. Use `true` when
     *              called in response to an actual USB attach event or from [UsbAttachedActivity],
     *              because the user has explicitly plugged in a device. Use `false` (default)
     *              for the startup scan in [onCreate].
     */
    private fun checkAlreadyConnectedUsb(force: Boolean = false) {
        val settings = App.provide(this).settings
        val lastSession = settings.autoConnectLastSession
        val singleUsb = settings.autoConnectSingleUsbDevice
        val usbAutoStart = settings.autoStartOnUsb

        if (!force && !lastSession && !singleUsb && !usbAutoStart) return
        if (commManager.isConnected ||
            commManager.connectionState.value is CommManager.ConnectionState.Connecting ||
            isSwitchingToAccessory.get()) return

        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val deviceList = usbManager.deviceList.values.filter { UsbDeviceCompat.isAndroidDevice(it) }

        // Check for devices already in accessory mode first.
        // After AOA switch the device re-enumerates and appears as a new USB device — we must
        // request permission for this new device before openDevice(), or SecurityException occurs.
        for (device in deviceList) {
            if (UsbDeviceCompat.isInAccessoryMode(device)) {
                val deviceName = UsbDeviceCompat(device).uniqueName
                AppLog.i("Found device already in accessory mode: $deviceName")
                if (!usbManager.hasPermission(device)) {
                    AppLog.i("Accessory-mode device has no permission (re-enumerated); requesting permission: $deviceName")
                    requestUsbPermission(device)
                    return
                }
                isSwitchingToAccessory.set(true)
                serviceScope.launch {
                    try {
                        connectUsbWithRetry(device)
                    } finally {
                        isSwitchingToAccessory.set(false)
                    }
                }
                return
            }
        }

        // Last-session mode: reconnect to a known/allowed device
        if (lastSession) {
            for (device in deviceList) {
                val deviceCompat = UsbDeviceCompat(device)
                if (settings.isConnectingDevice(deviceCompat)) {
                    if (usbManager.hasPermission(device)) {
                        AppLog.i("Found known USB device with permission: ${deviceCompat.uniqueName}. Switching to accessory mode.")
                        isSwitchingToAccessory.set(true)
                        val usbMode = UsbAccessoryMode(usbManager)
                        serviceScope.launch(Dispatchers.IO) {
                            try {
                                if (usbMode.connectAndSwitch(device, settings.useLibusb)) {
                                    AppLog.i("Successfully requested switch to accessory mode for ${deviceCompat.uniqueName}")
                                } else {
                                    AppLog.w("connectAndSwitch failed for ${deviceCompat.uniqueName}")
                                }
                            } finally {
                                isSwitchingToAccessory.set(false)
                            }
                        }
                        return
                    } else {
                        AppLog.i("Found known USB device but no permission: ${deviceCompat.uniqueName}, requesting...")
                        requestUsbPermission(device)
                        return
                    }
                }
            }
        }

        // USB auto-start mode: attempt AOA switch for any single non-accessory device
        if (usbAutoStart) {
            val nonAccessoryDevices = deviceList.filter { !UsbDeviceCompat.isInAccessoryMode(it) }
            if (nonAccessoryDevices.size == 1) {
                performSingleUsbConnect(nonAccessoryDevices[0])
                return
            }
        }

        // Single-USB mode: connect if there's exactly one candidate device.
        // If the user has marked specific devices as "Allowed" in the USB list,
        // only count those — so non-AA peripherals (dashcams, USB audio, etc.)
        // don't prevent auto-connect. Falls back to counting all devices when
        // no devices have been explicitly allowed (fresh install).
        if (singleUsb) {
            val nonAccessoryDevices = deviceList.filter { !UsbDeviceCompat.isInAccessoryMode(it) }
            val allowed = settings.allowedDevices
            val candidates = if (allowed.isNotEmpty()) {
                nonAccessoryDevices.filter { allowed.contains(UsbDeviceCompat(it).uniqueName) }
            } else {
                nonAccessoryDevices
            }
            if (allowed.isNotEmpty() && candidates.size != nonAccessoryDevices.size) {
                AppLog.i("Single USB auto-connect: ${nonAccessoryDevices.size} USB device(s) present, ${candidates.size} allowed")
            }
            if (candidates.size == 1) {
                performSingleUsbConnect(candidates[0])
                return
            }
        }

        // Fallback: if force=true and we have a single Google VID device in normal mode,
        // switch it to accessory mode. This handles cases where UsbAttachedActivity didn't fire.
        if (force) {
            val nonAccessoryDevices = deviceList.filter { !UsbDeviceCompat.isInAccessoryMode(it) }
            val googleDevices = nonAccessoryDevices.filter { it.vendorId == 0x18D1 }
            if (googleDevices.size == 1) {
                AppLog.i("Fallback: force=true and found single Google normal-mode device ${UsbDeviceCompat(googleDevices[0]).uniqueName}. Switching to accessory mode.")
                performSingleUsbConnect(googleDevices[0])
            }
        }
    }

    private fun performSingleUsbConnect(device: UsbDevice) {
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        if (usbManager.hasPermission(device)) {
            val deviceName = UsbDeviceCompat(device).uniqueName
            AppLog.i("Single USB auto-connect: connecting to $deviceName")
            isSwitchingToAccessory.set(true)
            val usbMode = UsbAccessoryMode(usbManager)
            serviceScope.launch(Dispatchers.IO) {
                try {
                    if (usbMode.connectAndSwitch(device, settings.useLibusb)) {
                        AppLog.i("Successfully requested switch to accessory mode for single USB device. Waiting for re-enumeration...")
                    } else {
                        AppLog.w("Single USB auto-connect: connectAndSwitch failed for $deviceName")
                    }
                } finally {
                    isSwitchingToAccessory.set(false)
                }
            }
        } else {
            AppLog.i("Single USB auto-connect: device found but no permission, requesting...")
            requestUsbPermission(device)
        }
    }

    // -------------------------------------------------------------------------
    // Connection
    // -------------------------------------------------------------------------

    /**
     * Attempts a USB connection up to [maxRetries] times with a 1.5 s delay between attempts.
     *
     * USB accessories occasionally fail on the first attach (the device hasn't fully
     * enumerated yet), so retrying is necessary for reliability.
     */
    private suspend fun connectUsbWithRetry(device: UsbDevice, maxRetries: Int = 3) {
        var retryCount = 0
        var success = false
        while (retryCount <= maxRetries && !success) {
            if (retryCount > 0) {
                AppLog.i("Retrying USB connection (attempt ${retryCount + 1}/$maxRetries)...")
                delay(1500)
                // A USB reattach during the delay could have already started a new connection;
                // bail out to avoid two parallel retry loops competing on the same device.
                if (commManager.isConnected ||
                    commManager.connectionState.value is CommManager.ConnectionState.Connecting) return
            }
            commManager.connect(device)
            success = commManager.connectionState.value is CommManager.ConnectionState.Connected
            retryCount++
        }
    }

    // -------------------------------------------------------------------------
    // Notification
    // -------------------------------------------------------------------------

    private fun createNotification(): Notification {
        val stopPendingIntent = PendingIntent.getService(
            this, 0,
            Intent(this, AapService::class.java).apply { action = ACTION_STOP_SERVICE },
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE
            else PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Tap the notification to go back to the projection screen (if connected) or home
        val (notificationIntent, requestCode) = if (commManager.isConnected) {
            AapProjectionActivity.intent(this).apply {
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            } to 100
        } else {
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            } to 101
        }

        val contentText = if (commManager.isConnected)
            getString(R.string.notification_projection_active)
        else
            getString(R.string.notification_service_running)

        return NotificationCompat.Builder(this, App.defaultChannel)
            .setSmallIcon(R.drawable.ic_stat_aa)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentTitle("ssHeadUnit")
            .setContentText(contentText)
            .setContentIntent(PendingIntent.getActivity(
                this, requestCode, notificationIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
            ))
            .addAction(R.drawable.ic_exit_to_app_white_24dp, getString(R.string.exit), stopPendingIntent)
            .build()
    }

    private fun updateNotification() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(1, createNotification())
    }

    /**
     * Launch MainActivity after boot using a cascading fallback chain designed
     * to work across stock AOSP head units, Xiaomi MIUI/HyperOS, Samsung One UI,
     * Huawei EMUI, OPPO ColorOS, and other OEM ROMs.
     *
     * Strategy order:
     * 1. Direct startActivity (Android < 10, or any device without background
     *    activity restrictions — works on most head units running AOSP)
     * 2. Overlay window trampoline (Android 10+): creates a zero-size invisible
     *    overlay giving the app a "visible" context. Bypasses MIUI, EMUI, ColorOS
     *    background start restrictions. Requires SYSTEM_ALERT_WINDOW.
     * 3. Full-screen intent notification (Android 10+): high-priority notification
     *    with fullScreenIntent. Works on stock Android 10-13 and Samsung. On
     *    Android 14+ needs USE_FULL_SCREEN_INTENT permission.
     * 4. Tap-to-open notification (last resort): user taps notification to open.
     */
    /**
     * Launches MainActivity when reopenOnReconnection is enabled and no activity is currently
     * visible. Uses the same overlay trampoline technique as boot auto-start to bypass OEM
     * background activity start restrictions.
     */
    private fun launchMainActivityIfNeeded(source: String) {
        val settings = App.provide(this).settings
        if (!settings.autoStartOnUsb || !settings.reopenOnReconnection) return

        AppLog.i("Reopen on reconnection: launching MainActivity ($source)")
        launchMainActivityOnBoot()
    }

    private fun launchMainActivityOnBoot() {
        // Android < 10: no background activity start restrictions
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            AppLog.i("Boot auto-start: launching directly (API ${Build.VERSION.SDK_INT} < 29)")
            launchDirectly()
            return
        }

        // Android 10+: try overlay trampoline (bypasses all known OEM restrictions)
        if (AppPermissions.isOverlayGranted(this)) {
            AppLog.i("Boot auto-start: launching via overlay window trampoline")
            if (launchViaOverlayTrampoline()) return
        }

        // Fallback: full-screen intent notification
        AppLog.i("Boot auto-start: falling back to full-screen intent notification")
        launchViaFullScreenIntent()
    }

    private fun launchDirectly() {
        try {
            val launchIntent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(MainActivity.EXTRA_LAUNCH_SOURCE, "Boot auto-start")
            }
            startActivity(launchIntent)
            AppLog.i("Boot auto-start: direct startActivity succeeded")
        } catch (e: Exception) {
            AppLog.e("Boot auto-start: direct startActivity failed: ${e.message}")
            launchViaFullScreenIntent()
        }
    }

    private fun launchViaOverlayTrampoline(): Boolean {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(MainActivity.EXTRA_LAUNCH_SOURCE, "Boot auto-start")
        }
        return launchViaOverlayTrampoline(launchIntent)
    }

    private fun launchViaOverlayTrampoline(launchIntent: Intent): Boolean {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT

        val params = WindowManager.LayoutParams(
            0, 0, overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        val view = View(this)
        return try {
            wm.addView(view, params)
            startActivity(launchIntent)
            AppLog.i("Overlay trampoline: startActivity succeeded")
            true
        } catch (e: Exception) {
            AppLog.e("Overlay trampoline failed: ${e.message}")
            false
        } finally {
            try { wm.removeView(view) } catch (_: Exception) {}
        }
    }

    private fun launchViaFullScreenIntent() {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(MainActivity.EXTRA_LAUNCH_SOURCE, "Boot auto-start")
        }
        val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        val fullScreenPi = PendingIntent.getActivity(this, 200, launchIntent, piFlags)

        val notification = NotificationCompat.Builder(this, App.bootStartChannel)
            .setSmallIcon(R.drawable.ic_stat_aa)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_service_running))
            .setFullScreenIntent(fullScreenPi, true)
            .setContentIntent(fullScreenPi)
            .setAutoCancel(true)
            .build()

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(BOOT_START_NOTIFICATION_ID, notification)

        // Dismiss the boot notification after a short delay
        serviceScope.launch {
            delay(5000)
            nm.cancel(BOOT_START_NOTIFICATION_ID)
        }
    }

    // -------------------------------------------------------------------------
    // Boot-loop guard
    // -------------------------------------------------------------------------

    // -------------------------------------------------------------------------
    // Companion
    // -------------------------------------------------------------------------

    companion object {
        /**
         * If set to `true`, the service will call [System.exit] at the very end of [onDestroy].
         * This is used by `killOnDisconnect` to ensure all cleanup (like Car Mode) completes
         * before the process dies.
         */
        var killProcessOnDestroy: Boolean = false

        private const val BOOT_START_NOTIFICATION_ID = 42
        private const val PROJECTION_LAUNCH_NOTIFICATION_ID = 43

        // Service action strings used with startService() and sendBroadcast()
        const val ACTION_CHECK_USB                 = "com.skystream.ssheadunit.ACTION_CHECK_USB"
        const val ACTION_STOP_SERVICE              = "com.skystream.ssheadunit.aap.action.STOP_SERVICE"
        const val ACTION_DISCONNECT                = "com.skystream.ssheadunit.ACTION_DISCONNECT"
        const val ACTION_REQUEST_NIGHT_MODE_UPDATE = "com.skystream.ssheadunit.aap.action.REQUEST_NIGHT_MODE_UPDATE"
        const val ACTION_NIGHT_MODE_CHANGED      = "com.skystream.ssheadunit.ACTION_NIGHT_MODE_CHANGED"
        const val ACTION_ORIENTATION_CHANGED     = "com.skystream.ssheadunit.ACTION_ORIENTATION_CHANGED"
        const val ACTION_REFRESH_SENSORS         = "com.skystream.ssheadunit.aap.action.REFRESH_SENSORS"
        const val ACTION_RESTART_AUDIO           = "com.skystream.ssheadunit.aap.action.RESTART_AUDIO"

        /** Max handshake failures on a stale accessory device before forcing AOA re-enumeration. */
        private const val MAX_STALE_ACCESSORY_RETRIES = 1

        /** Delay before retrying USB connection after an unexpected disconnect. */
        private const val USB_RECONNECT_DELAY_MS = 3000L

        /** Delay before AapService tries to handle a normal-mode USB attach as a fallback
         *  when UsbAttachedActivity doesn't fire (common on Chinese MediaTek headunits). */
        private const val USB_ATTACH_FALLBACK_DELAY_MS = 2000L

        /** Screen-off duration (ms) above which SCREEN_ON is treated as a hibernate wake.
         *  60 seconds filters out normal screen timeouts while catching any hibernate/quick boot. */
        private const val HIBERNATE_WAKE_THRESHOLD_MS = 60_000L

        const val EXTRA_MAC = "extra_mac"
        const val EXTRA_ENDPOINT_ID = "extra_endpoint_id"
    }
}
