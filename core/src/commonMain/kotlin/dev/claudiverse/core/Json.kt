package dev.claudiverse.core

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/*
 * Lenient reads over the server's JSON, with the semantics the app had on
 * org.json: a missing key and an explicit null both read as absent, a scalar
 * reads as text whatever its JSON type, and a wrong type is absent rather than
 * an exception. Messages are Claude Code's own and change shape between
 * versions; a field of the wrong type must not take the transcript down.
 */

/** The value as text, or null when missing or null. Numbers and booleans read as their text. */
internal fun JsonObject.str(key: String): String? = when (val v = this[key]) {
    null, JsonNull -> null
    is JsonPrimitive -> v.content
    else -> v.toString()
}

/** [str], with absent as "". */
internal fun JsonObject.text(key: String): String = str(key).orEmpty()

internal fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)
    ?.takeUnless { it is JsonNull }
    ?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }

internal fun JsonObject.int(key: String): Int? = long(key)?.toInt()

/** A number, whole or not; null when missing, null or not a number. */
internal fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)
    ?.takeUnless { it is JsonNull || it.isString }
    ?.doubleOrNull

/** Null when missing or null, else [flag]. */
internal fun JsonObject.bool(key: String): Boolean? = when (this[key]) {
    null, JsonNull -> null
    else -> flag(key)
}

/** A field the row cannot exist without: missing, it fails the read rather than inventing a value. */
internal fun JsonObject.requireLong(key: String): Long =
    long(key) ?: throw ApiException("Server sent a $key that is missing or not a number")

internal fun JsonObject.requireStr(key: String): String =
    str(key) ?: throw ApiException("Server sent no $key")

/** The array's items as text; empty for no array. */
internal fun JsonArray?.strings(): List<String> = this?.map { (it as? JsonPrimitive)?.content ?: it.toString() }.orEmpty()

/** True for `true` or the text "true" (any case); anything else is false. */
internal fun JsonObject.flag(key: String): Boolean =
    (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content.equals("true", ignoreCase = true)

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray

/** The array's objects, skipping anything else; empty for no array. */
internal fun JsonArray?.objects(): List<JsonObject> = this?.filterIsInstance<JsonObject>().orEmpty()

@OptIn(ExperimentalSerializationApi::class)
private val pretty = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
}

/** Indented JSON, for showing a tool's input. */
internal fun JsonElement.pretty(): String = pretty.encodeToString(JsonElement.serializer(), this)

/** One decimal place, halves away from zero as "%.1f" rounds them; common Kotlin has no String.format. */
internal fun oneDecimal(value: Double): String {
    val tenths = kotlin.math.floor(kotlin.math.abs(value) * 10 + 0.5).toLong()
    val sign = if (value < 0 && tenths != 0L) "-" else ""
    return "$sign${tenths / 10}.${tenths % 10}"
}
