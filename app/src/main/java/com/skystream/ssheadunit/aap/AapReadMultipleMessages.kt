package com.skystream.ssheadunit.aap

import com.skystream.ssheadunit.aap.protocol.Channel
import com.skystream.ssheadunit.aap.protocol.messages.Messages
import com.skystream.ssheadunit.connection.AccessoryConnection
import com.skystream.ssheadunit.utils.AppLog
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer

internal class AapReadMultipleMessages(
        connection: AccessoryConnection,
        ssl: AapSsl,
        handler: AapMessageHandler)
    : AapRead.Base(connection, ssl, handler) {

    // Increase buffers to 4MB to handle large 1080p/4K/HEVC I-frames
    private val fifo = ByteBuffer.allocate(4 * 1024 * 1024) 
    private val recvBuffer = ByteArray(Messages.DEF_BUFFER_LENGTH)
    private val recvHeader = AapMessageIncoming.EncryptedHeader()
    private val msgBuffer = ByteArray(4 * 1024 * 1024) 
    private val skipBuffer = ByteArray(4)
    private var readCount = 0
    private var decryptedCount = 0
    private var decryptFailureStreak = 0

    override fun doRead(connection: AccessoryConnection): Int {
        val size = try {
            connection.recvBlocking(recvBuffer, recvBuffer.size, 5000, false)
        } catch (e: Exception) {
            AppLog.e("AapRead: Fatal read error: ${e.message}")
            return -1
        }

        if (size < 0) {
            // If the connection is dead (e.g. resetInterface failed to re-claim),
            // signal the transport to quit instead of spinning on a broken connection.
            if (!connection.isConnected) {
                AppLog.e("AapRead: Connection lost. Stopping read loop.")
                fifo.clear()
                return -1
            }
            // It was a timeout or temporary error. Do NOT clear the FIFO because USB/TCP
            // is reliable and no bytes were lost. Discarding FIFO would desynchronize the stream.
            return 0
        }
        if (size == 0) return 0

        try {
            readCount++
            if (readCount <= 5 || AppLog.LOG_VERBOSE) {
                AppLog.i("AapReadBulk: recv #%d size=%d fifoPos=%d fifoRemaining=%d preview=%s",
                    readCount, size, fifo.position(), fifo.remaining(),
                    AapDiagnostics.hexPreview(recvBuffer, 0, size))
            }
            if (fifo.remaining() < size) {
                AppLog.w("AapRead: FIFO overflow! Size: $size, Remaining: ${fifo.remaining()}. Clearing buffer.")
                fifo.clear()
            }
            fifo.put(recvBuffer, 0, size)
            processBulk()
        } catch (e: Exception) {
            AppLog.e("AapRead: Error in processBulk: ${e.message}")
            fifo.clear() // Hard reset on error
        }
        return 0
    }

    private fun processBulk() {
        fifo.flip()

        while (fifo.remaining() >= AapMessageIncoming.EncryptedHeader.SIZE) {
            fifo.mark()
            fifo.get(recvHeader.buf, 0, recvHeader.buf.size)
            recvHeader.decode()

            if (recvHeader.flags == 0x09) {
                if (fifo.remaining() < 4) {
                    fifo.reset()
                    break
                }
                fifo.get(skipBuffer, 0, 4)
                AppLog.d("AapReadBulk: Fragment total-size prefix for chan=%d %s flags=0x%02x enc_len=%d prefix=%s",
                    recvHeader.chan, Channel.name(recvHeader.chan), recvHeader.flags, recvHeader.enc_len,
                    AapDiagnostics.hexPreview(skipBuffer))
            }

            if (recvHeader.enc_len > msgBuffer.size || recvHeader.enc_len < 0) {
                AppLog.e("AapRead: Invalid message length (${recvHeader.enc_len}). Resetting FIFO. chan=${recvHeader.chan} ${Channel.name(recvHeader.chan)} flags=0x${recvHeader.flags.toString(16)} header=${AapDiagnostics.hexPreview(recvHeader.buf)} fifoRemaining=${fifo.remaining()}")
                fifo.clear()
                return 
            }

            if (fifo.remaining() < recvHeader.enc_len) {
                fifo.reset()
                break
            }

            fifo.get(msgBuffer, 0, recvHeader.enc_len)

            try {
                val msg = AapMessageIncoming.decrypt(recvHeader, 0, msgBuffer, ssl)

                if (msg != null) {
                    decryptFailureStreak = 0
                    decryptedCount++
                    if (decryptedCount <= 10 || msg.channel == Channel.ID_VID || AppLog.LOG_VERBOSE) {
                        AppLog.i("AapReadBulk: decrypted #%d chan=%d %s flags=0x%02x type=0x%04x size=%d dataOffset=%d",
                            decryptedCount, msg.channel, Channel.name(msg.channel), msg.flags.toInt() and 0xFF,
                            msg.type, msg.size, msg.dataOffset)
                    }
                    handler.handle(msg)
                } else {
                    decryptFailureStreak++
                    AppLog.e("AapReadBulk: decrypt returned null streak=%d chan=%d %s flags=0x%02x enc_len=%d header=%s encryptedPreview=%s fifoRemaining=%d",
                        decryptFailureStreak, recvHeader.chan, Channel.name(recvHeader.chan), recvHeader.flags,
                        recvHeader.enc_len, AapDiagnostics.hexPreview(recvHeader.buf),
                        AapDiagnostics.hexPreview(msgBuffer, 0, recvHeader.enc_len), fifo.remaining())
                }
            } catch (e: Exception) {
                AppLog.e("AapRead: Decryption/Handling error: ${e.message}. chan=${recvHeader.chan} ${Channel.name(recvHeader.chan)} flags=0x${recvHeader.flags.toString(16)} enc_len=${recvHeader.enc_len} header=${AapDiagnostics.hexPreview(recvHeader.buf)} encryptedPreview=${AapDiagnostics.hexPreview(msgBuffer, 0, recvHeader.enc_len)}")
            }
        }

        fifo.compact()
    }
}
