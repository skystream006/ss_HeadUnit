package com.skystream.ssheadunit.aap

import android.content.Context
import com.skystream.ssheadunit.connection.AccessoryConnection
import com.skystream.ssheadunit.decoder.MicRecorder
import com.skystream.ssheadunit.utils.AppLog
import com.skystream.ssheadunit.aap.protocol.proto.MediaPlayback
import com.skystream.ssheadunit.utils.Settings

internal interface AapRead {
    fun read(): Int

    abstract class Base internal constructor(
            private val connection: AccessoryConnection?,
            internal val ssl: AapSsl,
            internal val handler: AapMessageHandler) : AapRead {

        override fun read(): Int {
            if (connection == null) {
                AppLog.e("No connection.")
                return -1
            }

            return doRead(connection)
        }

        protected abstract fun doRead(connection: AccessoryConnection): Int
    }

    object Factory {
        fun create(
            connection: AccessoryConnection,
            transport: AapTransport,
            recorder: MicRecorder,
            aapAudio: AapAudio,
            aapVideo: AapVideo,
            settings: Settings,
            context: Context,
            onAaMediaMetadata: ((MediaPlayback.MediaMetaData) -> Unit)? = null,
            onAaPlaybackStatus: ((MediaPlayback.MediaPlaybackStatus) -> Unit)? = null
        ): AapRead {
            val handler = AapMessageHandlerType(
                transport,
                recorder,
                aapAudio,
                aapVideo,
                settings,
                context,
                onAaMediaMetadata,
                onAaPlaybackStatus
            )

            return AapReadMultipleMessages(connection, transport.ssl, handler)
        }
    }
}
