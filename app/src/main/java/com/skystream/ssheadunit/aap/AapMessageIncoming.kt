package com.skystream.ssheadunit.aap

import com.skystream.ssheadunit.aap.protocol.Channel
import com.skystream.ssheadunit.aap.protocol.MsgType
import com.skystream.ssheadunit.utils.AppLog

internal class AapMessageIncoming(header: EncryptedHeader, ba: ByteArrayWithLimit)
    : AapMessage(header.chan, header.flags.toByte(), Utils.bytesToInt(ba.data, 0, true), calcOffset(header), ba.limit, ba.data) {

    internal class EncryptedHeader {

        var chan: Int = 0
        var flags: Int = 0
        var enc_len: Int = 0
        var msg_type: Int = 0
        var buf = ByteArray(SIZE)

        fun decode() {
            this.chan = buf[0].toInt() and 0xFF
            this.flags = buf[1].toInt() and 0xFF

            // Encoded length of bytes to be decrypted (minus 4/8 byte headers)
            this.enc_len = Utils.bytesToInt(buf, 2, true)
        }

        companion object {
            const val SIZE = 4
        }

    }

    companion object {

        fun decrypt(header: EncryptedHeader, offset: Int, buf: ByteArray, ssl: AapSsl): AapMessage? {
            val ba = if (header.flags and 0x08 == 0x08) {
                ssl.decrypt(offset, header.enc_len, buf)
            } else {
                val available = buf.size - offset
                if (header.enc_len <= 0 || header.enc_len > available) {
                    AppLog.e("Invalid plaintext payload length: enc_len=%d available=%d chan=%d %s flags=0x%02x header=%s payloadPreview=%s",
                        header.enc_len, available, header.chan, Channel.name(header.chan), header.flags,
                        AapDiagnostics.hexPreview(header.buf),
                        AapDiagnostics.hexPreview(buf, offset, available.coerceAtMost(32)))
                    return null
                }
                // Some adapters can emit plaintext AAP control frames after TLS setup.
                // These frames already contain [msg_type + payload], so parse them directly.
                AppLog.w("Plaintext AAP frame detected: enc_len=%d chan=%d %s flags=0x%02x header=%s payloadPreview=%s",
                    header.enc_len, header.chan, Channel.name(header.chan), header.flags,
                    AapDiagnostics.hexPreview(header.buf),
                    AapDiagnostics.hexPreview(buf, offset, header.enc_len))
                ByteArrayWithLimit(buf.copyOfRange(offset, offset + header.enc_len), header.enc_len)
            }
            if (ba == null) {
                AppLog.e("Decrypt failed: enc_len=%d chan=%d %s flags=0x%02x header=%s encryptedPreview=%s",
                    header.enc_len, header.chan, Channel.name(header.chan), header.flags,
                    AapDiagnostics.hexPreview(header.buf),
                    AapDiagnostics.hexPreview(buf, offset, header.enc_len))
                return null
            }

            if (ba.limit < 2) {
                AppLog.e("Decrypted payload too short: limit=%d enc_len=%d chan=%d %s flags=0x%02x header=%s decryptedPreview=%s",
                    ba.limit, header.enc_len, header.chan, Channel.name(header.chan), header.flags,
                    AapDiagnostics.hexPreview(header.buf),
                    AapDiagnostics.hexPreview(ba.data, 0, ba.limit))
                return null
            }

            header.msg_type = Utils.bytesToInt(ba.data, 0, true)
            val msg = AapMessageIncoming(header, ba)

            if (AppLog.LOG_VERBOSE) {
                AppLog.d("RECV: %s", msg.toString())
            }
            return msg
        }

        fun calcOffset(header: EncryptedHeader): Int {
            return 2
        }
    }
}
