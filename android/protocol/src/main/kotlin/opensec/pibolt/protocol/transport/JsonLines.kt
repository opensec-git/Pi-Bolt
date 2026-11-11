package opensec.pibolt.protocol.transport

/**
 * Splits a byte stream into JSONL records as Pi's RPC mode writes them (docs/rpc.md, Framing): records end at LF only,
 * an optional CR before it is dropped. U+2028/U+2029 inside JSON strings are not boundaries, which is why this works on
 * bytes rather than with a generic line reader.
 *
 * A record longer than [maxRecord] bytes is skipped (reported through [onOversized]) instead of growing the buffer
 * without bound.
 */
class JsonLines(
    private val maxRecord: Int = 128 * 1024 * 1024,
    private val onOversized: (Long) -> Unit = {},
    private val onRecord: (String) -> Unit,
) {
    private var buffer = ByteArray(64 * 1024)
    private var length = 0

    /** Bytes of the record being skipped, or -1 when not skipping. */
    private var skipped = -1L

    fun feed(chunk: ByteArray, count: Int = chunk.size) {
        var start = 0
        while (start < count) {
            var end = start
            while (end < count && chunk[end] != NEWLINE) end++
            if (end == count) {
                append(chunk, start, count - start)
                return
            }
            append(chunk, start, end - start)
            emit()
            start = end + 1
        }
    }

    /** End of stream: a final record without LF still counts. */
    fun finish() {
        if (length > 0 || skipped >= 0) emit()
    }

    private fun append(chunk: ByteArray, offset: Int, count: Int) {
        if (count == 0) return
        if (skipped >= 0) {
            skipped += count
            return
        }
        val needed = length + count
        if (needed > maxRecord) {
            skipped = needed.toLong()
            length = 0
            return
        }
        if (needed > buffer.size) buffer = buffer.copyOf(maxOf(needed, minOf(maxRecord, buffer.size * 2)))
        System.arraycopy(chunk, offset, buffer, length, count)
        length = needed
    }

    private fun emit() {
        if (skipped >= 0) {
            onOversized(skipped)
            skipped = -1
            length = 0
            return
        }
        var end = length
        if (end > 0 && buffer[end - 1] == CARRIAGE_RETURN) end--
        length = 0
        if (end == 0) return
        onRecord(String(buffer, 0, end, Charsets.UTF_8))
        // A huge record (a long session's get_messages) should not pin its buffer for the life of the engine.
        if (buffer.size > SHRINK_ABOVE) buffer = ByteArray(64 * 1024)
    }

    private companion object {
        const val NEWLINE = '\n'.code.toByte()
        const val CARRIAGE_RETURN = '\r'.code.toByte()
        const val SHRINK_ABOVE = 4 * 1024 * 1024
    }
}
