package com.z1pendant.hotkeys

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Lenient readers with org.json's (Android's) `optString` / `optBoolean` / `optLong` / `getInt` /
 * `getString` / `isNull` coercion rules, so hotkey files written by earlier versions (or edited by
 * hand) read exactly as before.
 */
internal object JsonCompat {
    private fun JsonElement?.coercedString(): String? = when (this) {
        null -> null
        JsonNull -> "null"                                  // org.json: String.valueOf(JSONObject.NULL)
        is JsonPrimitive -> content
        else -> toString()
    }

    fun JsonObject.optString(key: String, default: String = ""): String = this[key].coercedString() ?: default

    fun JsonObject.getString(key: String): String = this[key].coercedString() ?: throw IllegalArgumentException("No value for $key")

    fun JsonObject.optBoolean(key: String, default: Boolean = false): Boolean {
        val p = this[key] as? JsonPrimitive ?: return default
        if (p is JsonNull) return default
        return when {
            p.content.equals("true", ignoreCase = true) -> true
            p.content.equals("false", ignoreCase = true) -> false
            else -> default
        }
    }

    private fun JsonPrimitive.number(): Double? = if (this is JsonNull) null else content.toDoubleOrNull()

    fun JsonObject.optLong(key: String, default: Long): Long {
        val p = this[key] as? JsonPrimitive ?: return default
        if (!p.isString) p.content.toLongOrNull()?.let { return it }
        return p.number()?.toLong() ?: default
    }

    fun JsonObject.getInt(key: String): Int {
        val p = this[key] as? JsonPrimitive ?: throw IllegalArgumentException("No int value for $key")
        if (!p.isString) p.content.toIntOrNull()?.let { return it }
        return p.number()?.toInt() ?: throw IllegalArgumentException("Value for $key is not an int")
    }

    fun JsonObject.isNull(key: String): Boolean = this[key].let { it == null || it is JsonNull }

    fun JsonObject.optArray(key: String): JsonArray? = this[key] as? JsonArray

    fun JsonArray.getObject(i: Int): JsonObject = this[i] as? JsonObject ?: throw IllegalArgumentException("Value at $i is not an object")
}
