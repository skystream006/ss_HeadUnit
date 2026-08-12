package com.skystream.ssheadunit.view

import android.content.Context
import android.graphics.PixelFormat
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.skystream.ssheadunit.App
import com.skystream.ssheadunit.decoder.DecoderStopPolicy
import com.skystream.ssheadunit.decoder.VideoDecoder
import com.skystream.ssheadunit.utils.AppLog
import com.skystream.ssheadunit.utils.HeadUnitScreenConfig

data class ProjectionViewOptions(
    val name: String = "SurfaceView",
    val pixelFormat: Int? = null,
    val zOrderMediaOverlay: Boolean = false,
    val zOrderOnTop: Boolean = false
)

class ProjectionView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    private val options: ProjectionViewOptions = ProjectionViewOptions()
) : SurfaceView(context, attrs, defStyleAttr), IProjectionView, SurfaceHolder.Callback {

    private val callbacks = mutableListOf<IProjectionView.Callbacks>()
    private var videoDecoder: VideoDecoder? = null
    private var videoWidth = 0
    private var videoHeight = 0

    init {
        videoDecoder = App.provide(context).videoDecoder
        options.pixelFormat?.let { holder.setFormat(it) }
        if (options.zOrderOnTop) {
            setZOrderOnTop(true)
        } else if (options.zOrderMediaOverlay) {
            setZOrderMediaOverlay(true)
        }
        holder.addCallback(this)
        AppLog.i("${options.name}: initialized format=${options.pixelFormat ?: PixelFormat.UNKNOWN} mediaOverlay=${options.zOrderMediaOverlay} onTop=${options.zOrderOnTop}")
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        videoDecoder?.stop(DecoderStopPolicy.REASON_DETACHED_FROM_WINDOW)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        ProjectionViewScaler.updateScale(this, videoWidth, videoHeight)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        AppLog.i("holder $holder")
        callbacks.forEach { it.onSurfaceCreated(holder.surface) }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        AppLog.i("holder $holder, format: $format, width: $width, height: $height")
        callbacks.forEach { it.onSurfaceChanged(holder.surface, width, height) }
        ProjectionViewScaler.updateScale(this, videoWidth, videoHeight)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        AppLog.i("holder $holder")
        videoDecoder?.stop(DecoderStopPolicy.REASON_SURFACE_DESTROYED)
        callbacks.forEach { it.onSurfaceDestroyed(holder.surface) }
    }

    override fun addCallback(callback: IProjectionView.Callbacks) {
        callbacks.add(callback)
        if (holder.surface.isValid) {
            callback.onSurfaceCreated(holder.surface)
            callback.onSurfaceChanged(holder.surface, width, height)
        }
    }

    override fun removeCallback(callback: IProjectionView.Callbacks) {
        callbacks.remove(callback)
    }

    override fun setVideoSize(width: Int, height: Int) {
        if (videoWidth == width && videoHeight == height) return
        AppLog.i("Video size set to ${width}x$height")
        videoWidth = width
        videoHeight = height

        if (HeadUnitScreenConfig.forcedScale) {
            val settings = App.provide(context).settings
            if (settings.stretchToFill) {
                holder.setSizeFromLayout()
            } else {
                AppLog.i("FORCED SCALE: Setting fixed size to ${width}x$height")
                holder.setFixedSize(width, height)
            }
        } else {
            holder.setSizeFromLayout()
        }

        ProjectionViewScaler.updateScale(this, videoWidth, videoHeight)
    }

    override fun setVideoScale(scaleX: Float, scaleY: Float) {
        this.scaleX = scaleX
        this.scaleY = scaleY
    }

    // A plain SurfaceView is composited directly by SurfaceFlinger, so there is no
    // per-frame draw callback we can observe. Report "unsupported" so the projection
    // watchdog skips display-stall recovery for this backend (issue #650).
    override fun lastFrameDrawnMs(): Long = -1L

    override fun longFrameEvents(): Long = 0L
}
