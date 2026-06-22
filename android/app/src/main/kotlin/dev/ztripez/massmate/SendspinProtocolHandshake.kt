package dev.ztripez.massmate

import org.json.JSONArray
import org.json.JSONObject

/**
 * Minimal Sendspin hello/goodbye handshake parser and serializer.
 *
 * This object owns only the connection readiness gate: `client/hello`, `server/hello`, activated
 * role validation, and `client/goodbye`. Post-handshake message modeling and dispatch remain outside
 * this layer. [PROTOCOL_VERSION], [advertisedClientRoles], and [requiredClientRoles] are the native
 * invariants required before the local-player backend may report READY. Parsing is deliberately
 * strict: malformed JSON, missing fields, wrong field types, unsupported protocol versions, and
 * non-string role entries throw [SendspinConnectionException] with
 * [LocalPlayerEnvelope.LOCAL_PLAYER_PROTOCOL_ERROR]. Role mismatches throw
 * [LocalPlayerEnvelope.LOCAL_PLAYER_ROLE_MISMATCH].
 */
object SendspinProtocolHandshake {
    /** Sendspin protocol version accepted by this native handshake gate. */
    const val PROTOCOL_VERSION = 1

    private const val CLIENT_HELLO_TYPE = "client/hello"
    private const val SERVER_HELLO_TYPE = "server/hello"
    private const val CLIENT_GOODBYE_TYPE = "client/goodbye"
    private const val CLIENT_ID = "mass-mate-android"

    /** Roles advertised by Mass Mate in `client/hello`, in stable JSON array order. */
    val advertisedClientRoles: Set<String> = linkedSetOf(
        "audio",
        "controller",
        "state",
        "time",
    )

    /** Roles that must be activated by `server/hello` before the connection is READY. */
    val requiredClientRoles: Set<String> = advertisedClientRoles

    /** Serializes the initial client hello text frame. */
    fun clientHello(): String {
        return JSONObject()
            .put("type", CLIENT_HELLO_TYPE)
            .put("protocolVersion", PROTOCOL_VERSION)
            .put("clientId", CLIENT_ID)
            .put("roles", JSONArray(advertisedClientRoles.toList()))
            .toString()
    }

    /** Serializes the deliberate disconnect goodbye text frame. */
    fun clientGoodbye(): String = JSONObject().put("type", CLIENT_GOODBYE_TYPE).toString()

    /** Parses a strict `server/hello` text frame into [ServerHello]. */
    fun parseServerHello(text: String): ServerHello {
        val json = SendspinProtocolJson.parseObject(text, "Sendspin server hello")

        val type = SendspinProtocolJson.requiredString(json, "type", "Sendspin server hello")
        if (type != SERVER_HELLO_TYPE) {
            throw SendspinProtocolJson.protocolError("Expected Sendspin server hello, but received `$type`.")
        }

        val protocolVersion = SendspinProtocolJson.requiredInt(json, "protocolVersion", "Sendspin server hello")
        if (protocolVersion != PROTOCOL_VERSION) {
            throw SendspinProtocolJson.protocolError(
                "Unsupported Sendspin protocol version `$protocolVersion`.",
                mapOf("expected" to PROTOCOL_VERSION, "actual" to protocolVersion),
            )
        }

        return ServerHello(
            protocolVersion = protocolVersion,
            activatedRoles = SendspinProtocolJson.requiredStringSet(
                json,
                "activatedRoles",
                "Sendspin server hello",
            ),
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

}

/** Parsed `server/hello` payload used by the native handshake readiness gate. */
data class ServerHello(
    /** Server protocol version, which must equal [SendspinProtocolHandshake.PROTOCOL_VERSION]. */
    val protocolVersion: Int,

    /** Roles activated by the server; all required roles must be present and advertised. */
    val activatedRoles: Set<String>,
)
