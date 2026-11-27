package dev.krist.shieldremote.proto

import java.io.ByteArrayOutputStream

/** Minimal protobuf wire-format writer (proto2/proto3 compatible). */
class ProtoWriter {
    private val out = ByteArrayOutputStream()

    fun varintField(field: Int, value: Long): ProtoWriter {
        tag(field, 0); writeVarint(value); return this
    }

    fun intField(field: Int, value: Int): ProtoWriter = varintField(field, value.toLong()) // int32 negatives sign-extend to 10 bytes

    fun boolField(field: Int, value: Boolean): ProtoWriter = varintField(field, if (value) 1 else 0)

    fun bytesField(field: Int, value: ByteArray): ProtoWriter {
        tag(field, 2); writeVarint(value.size.toLong()); out.write(value); return this
    }

    fun stringField(field: Int, value: String): ProtoWriter = bytesField(field, value.toByteArray(Charsets.UTF_8))

    fun messageField(field: Int, block: ProtoWriter.() -> Unit): ProtoWriter =
        bytesField(field, ProtoWriter().apply(block).toByteArray())

    private fun tag(field: Int, wireType: Int) = writeVarint(((field shl 3) or wireType).toLong())

    private fun writeVarint(v: Long) {
        var value = v
        while (true) {
            if (value and 0x7FL.inv() == 0L) { out.write(value.toInt()); return }
            out.write(((value and 0x7F) or 0x80).toInt())
            value = value ushr 7
        }
    }

    fun toByteArray(): ByteArray = out.toByteArray()
}

class ProtoException(msg: String) : Exception(msg)

/** Minimal protobuf wire-format reader. */
class ProtoReader(private val buf: ByteArray, private var pos: Int = 0, private val end: Int = buf.size) {
    var field = 0; private set
    var wireType = 0; private set

    /** Advances to the next field; returns false at end of message. */
    fun next(): Boolean {
        if (pos >= end) return false
        val t = readVarint()
        field = (t ushr 3).toInt()
        wireType = (t and 7).toInt()
        if (field == 0) throw ProtoException("invalid tag 0")
        return true
    }

    fun readVarint(): Long {
        var result = 0L
        var shift = 0
        while (shift < 64) {
            if (pos >= end) throw ProtoException("truncated varint")
            val b = buf[pos++].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
        throw ProtoException("malformed varint")
    }

    fun readInt(): Int = readVarint().toInt()
    fun readBool(): Boolean = readVarint() != 0L

    fun readBytes(): ByteArray {
        val len = readVarint().toInt()
        if (len < 0 || pos + len > end) throw ProtoException("truncated bytes")
        val r = buf.copyOfRange(pos, pos + len); pos += len; return r
    }

    fun readString(): String = String(readBytes(), Charsets.UTF_8)

    fun skip() {
        when (wireType) {
            0 -> readVarint()
            1 -> pos += 8
            2 -> { val len = readVarint().toInt(); pos += len }
            5 -> pos += 4
            else -> throw ProtoException("unsupported wire type $wireType")
        }
        if (pos > end) throw ProtoException("truncated field")
    }
}

/** Varint length-prefixed framing used by both the pairing and the remote channel. */
object Framing {
    fun frame(payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(payload.size + 5)
        var v = payload.size
        while (v and 0x7F.inv() != 0) { out.write((v and 0x7F) or 0x80); v = v ushr 7 }
        out.write(v)
        out.write(payload)
        return out.toByteArray()
    }
}

/**
 * Incremental frame decoder. Feed arbitrary chunks (partial varints, multiple messages...)
 * and it emits complete payloads, mirroring androidtvremote2's ProtobufProtocol.data_received.
 */
class FrameDecoder(private val maxFrame: Int = 1 shl 20) {
    private var buffer = ByteArray(0)

    fun feed(data: ByteArray, len: Int = data.size): List<ByteArray> {
        buffer += data.copyOfRange(0, len)
        val frames = ArrayList<ByteArray>()
        while (buffer.isNotEmpty()) {
            var msgLen = 0L; var shift = 0; var i = 0; var complete = false
            while (i < buffer.size) {
                val b = buffer[i].toInt() and 0xFF
                msgLen = msgLen or ((b and 0x7F).toLong() shl shift)
                i++
                if (b and 0x80 == 0) { complete = true; break }
                shift += 7
                if (shift > 35) throw ProtoException("corrupt frame length")
            }
            if (!complete) break
            if (msgLen > maxFrame) throw ProtoException("frame too large: $msgLen")
            val endPos = i + msgLen.toInt()
            if (buffer.size < endPos) break
            frames += buffer.copyOfRange(i, endPos)
            buffer = buffer.copyOfRange(endPos, buffer.size)
        }
        return frames
    }
}
