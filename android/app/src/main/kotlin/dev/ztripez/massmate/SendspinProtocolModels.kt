package dev.ztripez.massmate

/**
 * Server state event parsed from native Sendspin protocol.
 *
 * @property playbackState Required protocol playback-state string for a future native snapshot
 * owner, not a Flutter UI enum.
 * @property positionMs Optional committed playback position in milliseconds. `null` means omitted
 * and must not clear previous native state by itself.
 * @property durationMs Optional media duration in milliseconds; `null` means unknown or omitted.
 * @property volume Optional normalized volume; `null` means omitted and native-to-Dart snapshot
 * mapping is not performed by this model.
 */
data class SendspinServerState(
    val playbackState: String,
    val positionMs: Long? = null,
    val durationMs: Long? = null,
    val volume: Double? = null,
)

/**
 * Media metadata parsed from native Sendspin protocol.
 *
 * Optional `null` properties mean the field was omitted, not that known UI metadata should be
 * erased. Native-to-Dart snapshot mapping is not performed by this model.
 *
 * @property title Optional track or stream title supplied by the server.
 * @property subtitle Optional secondary display line supplied by the server.
 * @property artist Optional artist name supplied by the server.
 * @property album Optional album name supplied by the server.
 * @property artworkUrl Optional artwork URL string supplied by the server.
 */
data class SendspinMetadata(
    val title: String? = null,
    val subtitle: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val artworkUrl: String? = null,
)

/**
 * Server time response used for native monotonic clock synchronization.
 *
 * @property requestId Client request identifier echoed by the server.
 * @property clientSentAtMs Client monotonic send time in milliseconds from the original request.
 * @property serverReceivedAtMs Server receive timestamp in the server clock domain.
 * @property serverSentAtMs Server send timestamp in the server clock domain.
 */
data class SendspinServerTime(
    val requestId: String,
    val clientSentAtMs: Long,
    val serverReceivedAtMs: Long,
    val serverSentAtMs: Long,
)

/** Stream codecs accepted as descriptors without enabling audio output. */
enum class SendspinStreamCodec(val wireValue: String) {
    /** Baseline PCM descriptor; actual audio writes are not performed by the buffer owner. */
    PCM("pcm"),
}

/**
 * Stream-start descriptor for the native stream buffer owner.
 *
 * @property streamId Required server stream identifier for binary frames.
 * @property codec Required validated codec descriptor. Validation does not claim audio support.
 * @property sampleRateHz Required sample rate in hertz. Unsupported values fail during parse.
 * @property channels Required channel count. Unsupported counts fail during parse.
 */
data class SendspinStreamStart(
    val streamId: String,
    val codec: SendspinStreamCodec,
    val sampleRateHz: Int,
    val channels: Int,
)

/**
 * Stream-clear descriptor for the native stream buffer owner.
 *
 * @property streamId Optional server stream identifier. `null` means clear all stream-owned native
 * state.
 */
data class SendspinStreamClear(val streamId: String? = null)

/**
 * Stream-end descriptor for the native stream buffer owner.
 *
 * @property streamId Required server stream identifier that ended.
 * @property reason Optional server reason string; `null` means no reason was supplied.
 */
data class SendspinStreamEnd(
    val streamId: String,
    val reason: String? = null,
)

/** Raw server command names parsed as protocol descriptors without Flutter intent mapping. */
enum class SendspinServerCommandKind(val wireValue: String) {
    /** Server requests playback start; Flutter intent mapping is not performed here. */
    PLAY("play"),

    /** Server requests playback pause; Flutter intent mapping is not performed here. */
    PAUSE("pause"),

    /** Server requests an absolute seek; Flutter intent mapping is not performed here. */
    SEEK_TO("seekTo"),

    /** Server requests a volume change; Flutter intent mapping is not performed here. */
    SET_VOLUME("setVolume"),
}

/**
 * Server-originated command event for a future native command owner.
 *
 * @property command Required validated raw Sendspin command. It is not a Flutter `PlaybackIntent`.
 * @property requestId Optional server request identifier for native replies when implemented.
 * @property positionMs Optional absolute position in milliseconds for seek-like commands.
 * @property volume Optional normalized volume for volume-like commands.
 */
data class SendspinServerCommand(
    val command: SendspinServerCommandKind,
    val requestId: String? = null,
    val positionMs: Long? = null,
    val volume: Double? = null,
)

/**
 * Server status event for native debug owners.
 *
 * @property status Required server status string.
 * @property message Optional diagnostic text; `null` means no message was sent.
 */
data class SendspinServerStatus(
    val status: String,
    val message: String? = null,
)

/**
 * Server-reported protocol error. Receipt always fails the active session visibly.
 *
 * @property code Required server error code string.
 * @property message Required server error message.
 */
data class SendspinServerProtocolError(
    val code: String,
    val message: String,
)
