package dev.ztripez.massmate

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Minimal Sendspin hello/goodbye handshake parser and serializer.
 *
 * This object owns only the connection readiness gate: `client/hello`, `server/hello`, activated
 * role validation, and `client/goodbye`. Full Sendspin message modeling and dispatch remain outside
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
        val json = try {
            JSONObject(text)
        } catch (error: JSONException) {
            throw protocolError("Malformed Sendspin server hello JSON.", mapOf("message" to error.message))
        }

        val type = readString(json, "type")
        if (type != SERVER_HELLO_TYPE) {
            throw protocolError("Expected Sendspin server hello, but received `$type`.")
        }

        val protocolVersion = readProtocolVersion(json)
        if (protocolVersion != PROTOCOL_VERSION) {
            throw protocolError(
                "Unsupported Sendspin protocol version `$protocolVersion`.",
                mapOf("expected" to PROTOCOL_VERSION, "actual" to protocolVersion),
            )
        }

        return ServerHello(
            protocolVersion = protocolVersion,
            activatedRoles = readStringSet(json, "activatedRoles"),
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

    private fun readString(json: JSONObject, field: String): String {
        if (!json.has(field)) {
            throw protocolError("Sendspin server hello is missing string field `$field`.")
        }
        val value = json.get(field)
        if (value !is String) {
            throw protocolError("Sendspin server hello field `$field` must be a string.")
        }
        return value
    }

    private fun readProtocolVersion(json: JSONObject): Int {
        val field = "protocolVersion"
        if (!json.has(field)) {
            throw protocolError("Sendspin server hello is missing integer field `$field`.")
        }
        val value = json.get(field)
        if (value !is Int) {
            throw protocolError("Sendspin server hello field `$field` must be an integer.")
        }
        return value
    }

    private fun readStringSet(json: JSONObject, field: String): Set<String> {
        if (!json.has(field)) {
            throw protocolError("Sendspin server hello is missing string array field `$field`.")
        }
        val value = json.get(field)
        if (value !is JSONArray) {
            throw protocolError("Sendspin server hello field `$field` must be an array.")
        }

        val roles = linkedSetOf<String>()
        for (index in 0 until value.length()) {
            val role = value.get(index)
            if (role !is String) {
                throw protocolError("Sendspin server hello field `$field` must contain only strings.")
            }
            roles.add(role)
        }
        return roles
    }

    private fun protocolError(
        message: String,
        details: Map<String, Any?>? = null,
    ): SendspinConnectionException = SendspinConnectionException(
        LocalPlayerEnvelope.LOCAL_PLAYER_PROTOCOL_ERROR,
        message,
        details,
    )
}

/** Parsed `server/hello` payload used by the native handshake readiness gate. */
data class ServerHello(
    /** Server protocol version, which must equal [SendspinProtocolHandshake.PROTOCOL_VERSION]. */
    val protocolVersion: Int,

    /** Roles activated by the server; all required roles must be present and advertised. */
    val activatedRoles: Set<String>,
)
