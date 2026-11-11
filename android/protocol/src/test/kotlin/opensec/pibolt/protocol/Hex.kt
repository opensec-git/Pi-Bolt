package opensec.pibolt.protocol

fun String.hex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
