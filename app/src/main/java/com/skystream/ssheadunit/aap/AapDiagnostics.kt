package com.skystream.ssheadunit.aap

internal object AapDiagnostics {
    fun hexPreview(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size, maxBytes: Int = 24): String {
        if (offset < 0 || length <= 0 || offset >= buffer.size) return ""
        val end = (offset + length).coerceAtMost(buffer.size)
        val previewLength = maxBytes.coerceIn(0, end - offset)
        val previewEnd = offset + previewLength
        val sb = StringBuilder()
        for (i in offset until previewEnd) {
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(String.format("%02X", buffer[i].toInt() and 0xFF))
        }
        if (previewEnd < end) sb.append(" ...")
        return sb.toString()
    }
}
