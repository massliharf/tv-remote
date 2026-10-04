package com.massliharf.tvremote.atv

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/** Just enough protobuf to speak the Android TV Remote v2 protocol without a code generator. */
class ProtoWriter {
    private val out = ByteArrayOutputStream()

    fun int(field: Int, value: Long): ProtoWriter {
        tag(field, 0)
        writeVarint(out, value)
        return this
    }

    fun int(field: Int, value: Int): ProtoWriter = int(field, value.toLong())

    fun bytes(field: Int, value: ByteArray): ProtoWriter {
        tag(field, 2)
        writeVarint(out, value.size.toLong())
        out.write(value)
        return this
    }

    fun string(field: Int, value: String): ProtoWriter = bytes(field, value.toByteArray(Charsets.UTF_8))

    fun msg(field: Int, value: ProtoWriter): ProtoWriter = bytes(field, value.toByteArray())

    fun toByteArray(): ByteArray = out.toByteArray()

    private fun tag(field: Int, wireType: Int) = writeVarint(out, ((field shl 3) or wireType).toLong())
}

/** A decoded message: field number -> values (Long for varints, ByteArray for length-delimited). */
class ProtoMessage private constructor(private val fields: Map<Int, List<Any>>) {

    fun has(field: Int) = fields.containsKey(field)

    fun int(field: Int): Long = fields[field]?.firstOrNull() as? Long ?: 0L

    fun bytes(field: Int): ByteArray? = fields[field]?.firstOrNull() as? ByteArray

    fun string(field: Int): String = bytes(field)?.toString(Charsets.UTF_8) ?: ""

    fun msg(field: Int): ProtoMessage? = bytes(field)?.let { parse(it) }

    companion object {
        fun parse(data: ByteArray): ProtoMessage {
            val fields = HashMap<Int, MutableList<Any>>()
            var pos = 0
            fun varint(): Long {
                var result = 0L
                var shift = 0
                while (true) {
                    val b = data[pos++].toInt() and 0xFF
                    result = result or ((b and 0x7F).toLong() shl shift)
                    if (b and 0x80 == 0) return result
                    shift += 7
                }
            }
            while (pos < data.size) {
                val key = varint()
                val field = (key ushr 3).toInt()
                val value: Any? = when ((key and 7).toInt()) {
                    0 -> varint()
                    1 -> { pos += 8; null }
                    2 -> {
                        val len = varint().toInt()
                        data.copyOfRange(pos, pos + len).also { pos += len }
                    }
                    5 -> { pos += 4; null }
                    else -> throw IllegalArgumentException("Unsupported wire type")
                }
                if (value != null) fields.getOrPut(field) { ArrayList() }.add(value)
            }
            return ProtoMessage(fields)
        }
    }
}

internal fun writeVarint(out: OutputStream, value: Long) {
    var v = value
    while (true) {
        if (v and 0x7FL.inv() == 0L) {
            out.write(v.toInt())
            return
        }
        out.write(((v and 0x7F) or 0x80).toInt())
        v = v ushr 7
    }
}

/** Messages on the wire are prefixed with their length as a varint. */
internal fun writeFrame(out: OutputStream, payload: ByteArray) {
    val buf = ByteArrayOutputStream()
    writeVarint(buf, payload.size.toLong())
    buf.write(payload)
    out.write(buf.toByteArray())
    out.flush()
}

/** Returns the next frame, or null at end of stream. */
internal fun readFrame(input: InputStream): ByteArray? {
    var len = 0L
    var shift = 0
    while (true) {
        val b = input.read()
        if (b < 0) return null
        len = len or ((b and 0x7F).toLong() shl shift)
        if (b and 0x80 == 0) break
        shift += 7
    }
    val data = ByteArray(len.toInt())
    var read = 0
    while (read < data.size) {
        val n = input.read(data, read, data.size - read)
        if (n < 0) throw EOFException()
        read += n
    }
    return data
}
