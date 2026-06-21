package dev.ztripez.massmate

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Shared strict JSON reader for native Sendspin protocol parsing. */
internal object SendspinProtocolJson {
    fun parseObject(text: String, description: String): JSONObject {
        return try {
            JSONObject(text)
        } catch (error: JSONException) {
            throw protocolError("Malformed $description JSON.", mapOf("message" to error.message))
        }
    }

    fun requiredString(json: JSONObject, field: String, description: String): String {
        val value = requiredValue(json, field, description)
        if (value !is String) throw fieldError(field, "string", description)
        return value
    }

    fun optionalString(json: JSONObject, field: String, description: String): String? {
        if (!json.has(field) || json.isNull(field)) return null
        val value = json.get(field)
        if (value !is String) throw fieldError(field, "string", description)
        return value
    }

    fun requiredInt(json: JSONObject, field: String, description: String): Int {
        val value = requiredValue(json, field, description)
        if (value !is Int) throw fieldError(field, "integer", description)
        return value
    }

    fun optionalLong(json: JSONObject, field: String, description: String): Long? {
        if (!json.has(field) || json.isNull(field)) return null
        return when (val value = json.get(field)) {
            is Int -> value.toLong()
            is Long -> value
            else -> throw fieldError(field, "integer", description)
        }
    }

    fun optionalDouble(json: JSONObject, field: String, description: String): Double? {
        if (!json.has(field) || json.isNull(field)) return null
        return when (val value = json.get(field)) {
            is Number -> value.toDouble()
            else -> throw fieldError(field, "number", description)
        }
    }

    fun requiredStringSet(json: JSONObject, field: String, description: String): Set<String> {
        val value = requiredValue(json, field, description)
        if (value !is JSONArray) throw fieldError(field, "array", description)

        val strings = linkedSetOf<String>()
        for (index in 0 until value.length()) {
            val item = value.get(index)
            if (item !is String) {
                throw protocolError("$description field `$field` must contain only strings.")
            }
            strings.add(item)
        }
        return strings
    }

    fun requireSupported(
        field: String,
        value: String,
        supportedValues: Set<String>,
        description: String,
    ): String {
        if (value in supportedValues) return value
        throw protocolError(
            "$description field `$field` has unsupported value `$value`.",
            mapOf("field" to field, "actual" to value, "supported" to supportedValues.toList()),
        )
    }

    fun requireSupported(
        field: String,
        value: Int,
        supportedValues: Set<Int>,
        description: String,
    ): Int {
        if (value in supportedValues) return value
        throw protocolError(
            "$description field `$field` has unsupported value `$value`.",
            mapOf("field" to field, "actual" to value, "supported" to supportedValues.toList()),
        )
    }

    fun protocolError(
        message: String,
        details: Map<String, Any?>? = null,
    ): SendspinConnectionException = SendspinConnectionException(
        LocalPlayerEnvelope.LOCAL_PLAYER_PROTOCOL_ERROR,
        message,
        details,
    )

    private fun requiredValue(json: JSONObject, field: String, description: String): Any {
        if (!json.has(field) || json.isNull(field)) {
            throw protocolError("$description is missing required field `$field`.")
        }
        return json.get(field)
    }

    private fun fieldError(field: String, expectedType: String, description: String): SendspinConnectionException =
        protocolError("$description field `$field` must be a $expectedType.")
}

/** Adds [field] only when [value] is non-null while preserving strict protocol serializers. */
internal fun JSONObject.putSendspinOptional(field: String, value: Any?): JSONObject {
    if (value != null) put(field, value)
    return this
}
