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
            this.chan = buf[0].toInt()
            this.flags = buf[1].toInt()

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
                    AppLog.e("Invalid plaintext payload length: enc_len=%d available=%d chan=%d flags=0x%02x",
                        header.enc_len, available, header.chan, header.flags)
                    return null
                }
                // Some adapters can emit plaintext AAP control frames after TLS setup.
                // These frames already contain [msg_type + payload], so parse them directly.
                AppLog.w("Plaintext AAP frame detected: enc_len=%d chan=%d %s flags=0x%02x",
                    header.enc_len, header.chan, Channel.name(header.chan), header.flags)
                ByteArrayWithLimit(buf.copyOfRange(offset, offset + header.enc_len), header.enc_len)
            }
            if (ba == null) {
                AppLog.e("WRONG FLAG: enc_len: %d  chan: %d %s flags: 0x%02x  msg_type: 0x%02x %s",
                    header.enc_len, header.chan, Channel.name(header.chan), header.flags, header.msg_type, MsgType.name(header.msg_type, header.chan))
                return null
            }

            if (ba.data.size < 2) {
                AppLog.e("Decrypted payload too short: " + ba.data.size)
                return null
            }

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
