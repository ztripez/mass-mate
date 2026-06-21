package dev.ztripez.massmate

import java.net.URI

/** Explicit Sendspin server settings supplied by the Flutter bridge. */
data class SendspinServerSettings(
    val serverUrl: String,
    val sendspinPath: String,
)

/** Builds validated WebSocket endpoint URLs from configured Music Assistant server settings. */
object SendspinEndpointBuilder {
    private const val DEFAULT_SENDSPIN_PATH = "/sendspin"

    /** Parses bridge arguments and returns a validated WebSocket endpoint URI. */
    fun fromBridgeArguments(arguments: Map<*, *>?): URI {
        val settings = fromMap(arguments)
        return buildEndpoint(settings)
    }

    private fun fromMap(arguments: Map<*, *>?): SendspinServerSettings {
        val serverUrl = arguments?.get("serverUrl") as? String
        if (serverUrl.isNullOrBlank()) {
            throw SendspinConnectionException(
                LocalPlayerEnvelope.LOCAL_PLAYER_ENDPOINT_INVALID,
                "MASS_MATE_SENDSPIN_SERVER_URL is required for the native local-player backend.",
                mapOf("field" to "serverUrl"),
            )
        }

        val path = (arguments.get("sendspinPath") as? String)
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_SENDSPIN_PATH

        return SendspinServerSettings(serverUrl.trim(), path.trim())
    }

    /** Converts http(s)/ws(s) server settings into the concrete Sendspin WebSocket endpoint. */
    fun buildEndpoint(settings: SendspinServerSettings): URI {
        val base = try {
            URI(settings.serverUrl)
        } catch (error: IllegalArgumentException) {
            throw SendspinConnectionException(
                LocalPlayerEnvelope.LOCAL_PLAYER_ENDPOINT_INVALID,
                "Configured Sendspin server URL is not a valid URI.",
                mapOf("serverUrl" to settings.serverUrl, "message" to error.message),
            )
        }

        val scheme = when (base.scheme?.lowercase()) {
            "http" -> "ws"
            "https" -> "wss"
            "ws" -> "ws"
            "wss" -> "wss"
            else -> throw SendspinConnectionException(
                LocalPlayerEnvelope.LOCAL_PLAYER_ENDPOINT_INVALID,
                "Configured Sendspin server URL must use http, https, ws, or wss.",
                mapOf("serverUrl" to settings.serverUrl, "scheme" to base.scheme),
            )
        }

        if (base.host.isNullOrBlank()) {
            throw SendspinConnectionException(
                LocalPlayerEnvelope.LOCAL_PLAYER_ENDPOINT_INVALID,
                "Configured Sendspin server URL must include a host.",
                mapOf("serverUrl" to settings.serverUrl),
            )
        }
        if (!settings.sendspinPath.startsWith('/')) {
            throw SendspinConnectionException(
                LocalPlayerEnvelope.LOCAL_PLAYER_ENDPOINT_INVALID,
                "Configured Sendspin endpoint path must start with `/`.",
                mapOf("sendspinPath" to settings.sendspinPath),
            )
        }

        val basePath = base.path.orEmpty().trimEnd('/')
        val endpointPath = settings.sendspinPath.trimStart('/')
        val combinedPath = if (basePath.isBlank()) {
            "/$endpointPath"
        } else {
            "$basePath/$endpointPath"
        }

        return try {
            URI(
                scheme,
                base.userInfo,
                base.host,
                base.port,
                combinedPath,
                null,
                null,
            )
        } catch (error: IllegalArgumentException) {
            throw SendspinConnectionException(
                LocalPlayerEnvelope.LOCAL_PLAYER_ENDPOINT_INVALID,
                "Configured Sendspin endpoint could not be built.",
                mapOf("serverUrl" to settings.serverUrl, "sendspinPath" to settings.sendspinPath),
            )
        }
    }
}

/** Typed Sendspin connection failure surfaced through local-player envelopes. */
class SendspinConnectionException(
    val code: String,
    override val message: String,
    val details: Map<String, Any?>? = null,
) : RuntimeException(message)
