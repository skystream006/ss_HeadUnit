package com.skystream.ssheadunit.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.skystream.ssheadunit.App
import com.skystream.ssheadunit.decoder.DecoderStopPolicy
import com.skystream.ssheadunit.decoder.SoftwareYuvFrameSink
import com.skystream.ssheadunit.decoder.VideoDecoder
import com.skystream.ssheadunit.utils.AppLog
import java.nio.ByteBuffer
import kotlin.math.max

class CanvasYuvProjectionView(
    context: Context,
    private val name: String = "CanvasYuvProjectionView"
) : SurfaceView(context), IProjectionView, SurfaceHolder.Callback, SoftwareYuvFrameSink {

    private val callbacks = mutableListOf<IProjectionView.Callbacks>()
    private var videoDecoder: VideoDecoder? = App.provide(context).videoDecoder
    private var videoWidth = 0
    private var videoHeight = 0
    private var bitmap: Bitmap? = null
    private var pixels = IntArray(0)

    @Volatile
    private var lastFrameDrawnMsValue: Long = 0L

    init {
        holder.addCallback(this)
        AppLog.i("$name: initialized software YUV canvas renderer")
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        videoDecoder?.stop(DecoderStopPolicy.REASON_DETACHED_FROM_WINDOW)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        callbacks.forEach { it.onSurfaceCreated(holder.surface) }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        callbacks.forEach { it.onSurfaceChanged(holder.surface, width, height) }
        ProjectionViewScaler.updateScale(this, videoWidth, videoHeight)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
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
        videoWidth = width
        videoHeight = height
        ProjectionViewScaler.updateScale(this, videoWidth, videoHeight)
    }

    override fun setVideoScale(scaleX: Float, scaleY: Float) {
        this.scaleX = scaleX
        this.scaleY = scaleY
    }

    override fun lastFrameDrawnMs(): Long = if (lastFrameDrawnMsValue > 0L) lastFrameDrawnMsValue else -1L

    override fun longFrameEvents(): Long = 0L

    override fun renderYuv420Frame(
        width: Int,
        height: Int,
        yPlane: ByteBuffer,
        yStride: Int,
        uPlane: ByteBuffer,
        uStride: Int,
        vPlane: ByteBuffer,
        vStride: Int
    ): Boolean {
        if (!holder.surface.isValid || width <= 0 || height <= 0) return false
        ensureBitmap(width, height)
        val out = pixels
        val yBase = yPlane.position()
        val uBase = uPlane.position()
        val vBase = vPlane.position()
        for (row in 0 until height) {
            val yRow = row * yStride
            val uRow = (row / 2) * uStride
            val vRow = (row / 2) * vStride
            val outRow = row * width
            for (col in 0 until width) {
                val y = yPlane.get(yBase + yRow + col).toInt() and 0xff
                val u = (uPlane.get(uBase + uRow + col / 2).toInt() and 0xff) - 128
                val v = (vPlane.get(vBase + vRow + col / 2).toInt() and 0xff) - 128
                out[outRow + col] = yuvToArgb(y, u, v)
            }
        }

        val frame = bitmap ?: return false
        frame.setPixels(out, 0, width, 0, 0, width, height)
        val canvas = holder.lockCanvas() ?: return false
        try {
            canvas.drawColor(Color.BLACK)
            canvas.drawBitmap(frame, null, targetRect(canvas, width, height), null)
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
        lastFrameDrawnMsValue = SystemClock.elapsedRealtime()
        return true
    }

    private fun ensureBitmap(width: Int, height: Int) {
        if (bitmap?.width == width && bitmap?.height == height) return
        bitmap?.recycle()
        bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        pixels = IntArray(width * height)
        setVideoSize(width, height)
        AppLog.i("$name: software canvas frame size ${width}x$height")
    }

    private fun targetRect(canvas: Canvas, width: Int, height: Int): Rect {
        val cw = canvas.width
        val ch = canvas.height
        if (cw <= 0 || ch <= 0) return Rect(0, 0, max(1, width), max(1, height))
        val scale = minOf(cw.toFloat() / width, ch.toFloat() / height)
        val dw = (width * scale).toInt()
        val dh = (height * scale).toInt()
        val left = (cw - dw) / 2
        val top = (ch - dh) / 2
        return Rect(left, top, left + dw, top + dh)
    }

    private fun yuvToArgb(y: Int, u: Int, v: Int): Int {
        val c = y - 16
        val r = clamp((298 * c + 409 * v + 128) shr 8)
        val g = clamp((298 * c - 100 * u - 208 * v + 128) shr 8)
        val b = clamp((298 * c + 516 * u + 128) shr 8)
        return (0xff shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun clamp(value: Int): Int = value.coerceIn(0, 255)
}
