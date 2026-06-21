package dev.ztripez.massmate

/** Minimal Sendspin handshake protocol owned by the native local-player backend. */
object SendspinProtocolHandshake {
    const val PROTOCOL_VERSION = 1

    val advertisedClientRoles: Set<String> = linkedSetOf(
        "audio",
        "controller",
        "state",
        "time",
    )
    val requiredClientRoles: Set<String> = advertisedClientRoles

    /** Creates the initial client hello text frame. */
    fun clientHello(): String {
        val roles = advertisedClientRoles.joinToString(",") { role -> "\"${escape(role)}\"" }
        return "{" +
            "\"type\":\"client/hello\"," +
            "\"protocolVersion\":$PROTOCOL_VERSION," +
            "\"clientId\":\"mass-mate-android\"," +
            "\"roles\":[$roles]" +
            "}"
    }

    /** Creates the deliberate disconnect goodbye text frame. */
    fun clientGoodbye(): String = "{\"type\":\"client/goodbye\"}"

    /** Parses and validates the server hello text frame. */
    fun parseServerHello(text: String): ServerHello {
        val type = readStringField(text, "type")
        if (type != "server/hello") {
            throw SendspinConnectionException(
                LocalPlayerEnvelope.LOCAL_PLAYER_PROTOCOL_ERROR,
                "Expected Sendspin server hello, but received `$type`.",
            )
        }

        val protocolVersion = readIntField(text, "protocolVersion")
        if (protocolVersion != PROTOCOL_VERSION) {
            throw SendspinConnectionException(
                LocalPlayerEnvelope.LOCAL_PLAYER_PROTOCOL_ERROR,
                "Unsupported Sendspin protocol version `$protocolVersion`.",
                mapOf("expected" to PROTOCOL_VERSION, "actual" to protocolVersion),
            )
        }

        return ServerHello(
            protocolVersion = protocolVersion,
            activatedRoles = readStringArrayField(text, "activatedRoles"),
        )
    }

    /** Validates server-activated roles against advertised and required client roles. */
    fun validateServerHello(serverHello: ServerHello) {
        val unexpectedRoles = serverHello.activatedRoles - advertisedClientRoles
        if (unexpectedRoles.isNotEmpty()) {
            throw SendspinConnectionException(
                LocalPlayerEnvelope.LOCAL_PLAYER_ROLE_MISMATCH,
                "Sendspin server activated roles this client did not advertise.",
                mapOf("unexpectedRoles" to unexpectedRoles.toList()),
            )
        }

        val missingRoles = requiredClientRoles - serverHello.activatedRoles
        if (missingRoles.isNotEmpty()) {
            throw SendspinConnectionException(
                LocalPlayerEnvelope.LOCAL_PLAYER_ROLE_MISMATCH,
                "Sendspin server did not activate required client roles.",
                mapOf(
                    "missingRoles" to missingRoles.toList(),
                    "activatedRoles" to serverHello.activatedRoles.toList(),
                ),
            )
        }
    }

    private fun readStringField(text: String, field: String): String {
        val match = Regex("\"${Regex.escape(field)}\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
            .find(text)
        if (match == null) {
            throw SendspinConnectionException(
                LocalPlayerEnvelope.LOCAL_PLAYER_PROTOCOL_ERROR,
                "Sendspin server hello is missing string field `$field`.",
            )
        }
        return unescape(match.groupValues[1])
    }

    private fun readIntField(text: String, field: String): Int {
        val match = Regex("\"${Regex.escape(field)}\"\\s*:\\s*([0-9]+)").find(text)
        if (match == null) {
            throw SendspinConnectionException(
                LocalPlayerEnvelope.LOCAL_PLAYER_PROTOCOL_ERROR,
                "Sendspin server hello is missing integer field `$field`.",
            )
        }
        return match.groupValues[1].toInt()
    }

    private fun readStringArrayField(text: String, field: String): Set<String> {
        val match = Regex("\"${Regex.escape(field)}\"\\s*:\\s*\\[(.*?)]", RegexOption.DOT_MATCHES_ALL)
            .find(text)
        if (match == null) {
            throw SendspinConnectionException(
                LocalPlayerEnvelope.LOCAL_PLAYER_PROTOCOL_ERROR,
                "Sendspin server hello is missing string array field `$field`.",
            )
        }

        val contents = match.groupValues[1].trim()
        if (contents.isEmpty()) return emptySet()

        return Regex("\"((?:[^\"\\\\]|\\\\.)*)\"")
            .findAll(contents)
            .map { result -> unescape(result.groupValues[1]) }
            .toCollection(linkedSetOf())
    }

    private fun escape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun unescape(value: String): String = value.replace("\\\"", "\"").replace("\\\\", "\\")
}

/** Parsed `server/hello` payload used only for the issue-26 handshake gate. */
data class ServerHello(
    val protocolVersion: Int,
    val activatedRoles: Set<String>,
)
