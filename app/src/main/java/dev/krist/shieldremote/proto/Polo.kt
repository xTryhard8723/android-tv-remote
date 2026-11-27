package dev.krist.shieldremote.proto

/** Pairing protocol (polo.proto, proto2) messages. Field numbers match androidtvremote2's polo.proto. */
object Polo {
    const val STATUS_OK = 200
    const val STATUS_ERROR = 400
    const val STATUS_BAD_CONFIGURATION = 401
    const val STATUS_BAD_SECRET = 402

    const val ENCODING_HEXADECIMAL = 3
    const val ROLE_INPUT = 1

    private fun outer(block: ProtoWriter.() -> Unit): ByteArray = ProtoWriter()
        .varintField(1, 2) // protocol_version = 2
        .varintField(2, STATUS_OK.toLong())
        .apply(block)
        .toByteArray()

    fun pairingRequest(clientName: String, serviceName: String = "atvremote"): ByteArray = outer {
        messageField(10) {
            stringField(1, serviceName) // required service_name
            stringField(2, clientName)
        }
    }

    fun options(): ByteArray = outer {
        messageField(20) {
            messageField(1) { varintField(1, ENCODING_HEXADECIMAL.toLong()); varintField(2, 6) } // input_encodings
            varintField(3, ROLE_INPUT.toLong()) // preferred_role
        }
    }

    fun configuration(): ByteArray = outer {
        messageField(30) {
            messageField(1) { varintField(1, ENCODING_HEXADECIMAL.toLong()); varintField(2, 6) }
            varintField(2, ROLE_INPUT.toLong())
        }
    }

    fun secret(secret: ByteArray): ByteArray = outer {
        messageField(40) { bytesField(1, secret) }
    }

    enum class Kind { PAIRING_REQUEST, PAIRING_REQUEST_ACK, OPTIONS, CONFIGURATION, CONFIGURATION_ACK, SECRET, SECRET_ACK, NONE }

    data class Message(val protocolVersion: Int, val status: Int, val kind: Kind, val serverName: String? = null)

    fun parse(bytes: ByteArray): Message {
        val r = ProtoReader(bytes)
        var version = 1; var status = 0; var kind = Kind.NONE; var serverName: String? = null
        while (r.next()) {
            when (r.field) {
                1 -> version = r.readInt()
                2 -> status = r.readInt()
                10 -> { r.readBytes(); kind = Kind.PAIRING_REQUEST }
                11 -> {
                    val sub = ProtoReader(r.readBytes())
                    while (sub.next()) if (sub.field == 1) serverName = sub.readString() else sub.skip()
                    kind = Kind.PAIRING_REQUEST_ACK
                }
                20 -> { r.readBytes(); kind = Kind.OPTIONS }
                30 -> { r.readBytes(); kind = Kind.CONFIGURATION }
                31 -> { r.readBytes(); kind = Kind.CONFIGURATION_ACK }
                40 -> { r.readBytes(); kind = Kind.SECRET }
                41 -> { r.readBytes(); kind = Kind.SECRET_ACK }
                else -> r.skip()
            }
        }
        return Message(version, status, kind, serverName)
    }
}
