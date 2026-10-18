package com.aeriotv.android.core.sync.dsync

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Dispatcharr Sync schema v3 (docs/DispatcharrSyncSchema.md): ONE App Data
 * key, `app.molinete.aerio`, so AerioTV uses 1 of a user's 25. It keeps the
 * envelope and holds one sub-object per category under `categories`, each
 * with its own `updatedAt`, `deviceId`, `data`, and optional `extra`, so the
 * per-category last-writer-wins and row merges are unchanged from v1.
 */
enum class DsyncBucket(val wire: String, val keys: List<DsyncKey>) {
    /** Schema v3: ONE key holds every category. */
    Main("app.molinete.aerio", DsyncKey.entries.toList()),
    ;

    companion object {
        fun of(key: DsyncKey): DsyncBucket = Main
        fun fromWire(name: String): DsyncBucket? = entries.firstOrNull { it.wire == name }

        /** The v2 three-key layout, read only by the migration. */
        val V2_WIRES = setOf("app.molinete.aerio", "app.molinete.aerio.favorites", "app.molinete.aerio.progress")
    }
}

object DsyncV2 {
    const val SCHEMA = 3
    /** The v2 sub-object shape is the same; only the key layout differed. */
    const val MIN_SUBOBJECT_SCHEMA = 2
    const val CATEGORIES = "categories"

    /** A decoded v2 key: envelope fields, raw category sub-objects by name, and the whole raw value. */
    data class Bucket(
        val schemaVersion: Int,
        val deviceId: String,
        val platform: String,
        val appVersion: String,
        val updatedAt: Long,
        val categories: Map<String, JsonObject>,
        val raw: JsonObject,
    )

    sealed interface Decode {
        data class Ok(val bucket: Bucket) : Decode
        data object Malformed : Decode
        data class TooNew(val version: Int) : Decode
    }

    fun decode(raw: JsonObject, known: Int = SCHEMA, min: Int = SCHEMA): Decode {
        val v = raw["schemaVersion"].asInt() ?: return Decode.Malformed
        val device = raw["deviceId"].asStr() ?: return Decode.Malformed
        val platform = raw["platform"].asStr() ?: return Decode.Malformed
        val app = raw["appVersion"].asStr() ?: return Decode.Malformed
        val at = raw["updatedAt"].asStr()?.let(DsyncEnvelopeCodec::parseIso) ?: return Decode.Malformed
        if (v > known) return Decode.TooNew(v)
        if (v < min) return Decode.Malformed
        val cats = raw[CATEGORIES] as? JsonObject ?: return Decode.Malformed
        val subs = cats.mapNotNull { (k, e) -> (e as? JsonObject)?.let { k to it } }.toMap()
        return Decode.Ok(Bucket(v, device, platform, app, at, subs, raw))
    }

    /**
     * One category as an envelope, so the v1 per-category logic runs as is.
     * Null when the category is absent; [DsyncDecode.Malformed] shape when it
     * lacks `updatedAt` or `data`.
     */
    fun category(b: Bucket, key: DsyncKey): DsyncDecode? {
        val sub = b.categories[key.suffix] ?: return null
        val at = sub["updatedAt"].asStr()?.let(DsyncEnvelopeCodec::parseIso) ?: return DsyncDecode.Malformed
        val data = sub["data"] as? JsonObject ?: return DsyncDecode.Malformed
        val dev = sub["deviceId"].asStr() ?: b.deviceId
        val extra = when (val e = sub["extra"]) {
            null, JsonNull -> null
            is JsonObject -> e
            else -> return DsyncDecode.Malformed
        }
        return DsyncDecode.Ok(DsyncEnvelope(b.schemaVersion, dev, b.platform, b.appVersion, at, data, extra, sub))
    }

    /** A category sub-object; unknown members and `extra` of [previous] are re-emitted. */
    fun encodeCategory(previous: JsonObject?, deviceId: String, updatedAt: Long, data: JsonObject): JsonObject {
        val out = LinkedHashMap<String, JsonElement>()
        out["updatedAt"] = JsonPrimitive(DsyncEnvelopeCodec.isoSeconds(updatedAt))
        out["deviceId"] = JsonPrimitive(deviceId)
        out["data"] = data
        previous?.forEach { (k, v) -> if (k !in out) out[k] = v }
        return JsonObject(out)
    }

    /**
     * The value to PUT for one key. Starts from [previous] (the last pulled
     * raw value) so categories this device did not touch, unknown categories,
     * and unknown envelope members are round-tripped; [replace] swaps in the
     * categories this device rebuilt.
     */
    fun encodeBucket(
        previous: JsonObject?,
        deviceId: String,
        appVersion: String,
        updatedAt: Long,
        replace: Map<DsyncKey, JsonObject>,
    ): JsonObject {
        val cats = LinkedHashMap<String, JsonElement>()
        (previous?.get(CATEGORIES) as? JsonObject)?.forEach { (k, v) -> cats[k] = v }
        replace.forEach { (k, v) -> cats[k.suffix] = v }
        val out = LinkedHashMap<String, JsonElement>()
        out["schemaVersion"] = JsonPrimitive(SCHEMA)
        out["deviceId"] = JsonPrimitive(deviceId)
        out["platform"] = JsonPrimitive("android")
        out["appVersion"] = JsonPrimitive(appVersion)
        out["updatedAt"] = JsonPrimitive(DsyncEnvelopeCodec.isoSeconds(updatedAt))
        out[CATEGORIES] = JsonObject(cats)
        previous?.forEach { (k, v) -> if (k !in out) out[k] = v }
        return JsonObject(out)
    }

    /** One 413 trim step on one category's `data`. */
    data class Trim(val key: DsyncKey, val label: String, val apply: (JsonObject) -> JsonObject)

    private fun JsonObject.rowsOf(): List<kotlinx.serialization.json.JsonArray> =
        (this["rows"] as? kotlinx.serialization.json.JsonArray)?.filterIsInstance<kotlinx.serialization.json.JsonArray>() ?: emptyList()

    private fun JsonObject.withMember(name: String, v: JsonElement): JsonObject = JsonObject(LinkedHashMap(this).apply { put(name, v) })

    private fun keepNewest(d: JsonObject, atPos: Int, n: Int): JsonObject =
        d.withMember("rows", kotlinx.serialization.json.JsonArray(d.rowsOf().sortedByDescending { it.getOrNull(atPos).asLong() ?: 0L }.take(n)))

    /**
     * The 413 trim sequence (schema v3, section 8): one step per retry, in
     * this order: progress oldest first, then recents, then watchlist and
     * hiddenTitles oldest first, then favorites from the end of the list.
     */
    val TRIMS: List<Trim> = listOf(
        Trim(DsyncKey.Progress, "progress: oldest down to 200") { keepNewest(it, 4, 200) },
        Trim(DsyncKey.Progress, "progress: oldest down to 100") { keepNewest(it, 4, 100) },
        Trim(DsyncKey.Recents, "recents: down to 25") { keepNewest(it, 1, 25) },
        Trim(DsyncKey.Watchlist, "watchlist: oldest down to 300") { keepNewest(it, 2, 300) },
        Trim(DsyncKey.HiddenTitles, "hiddenTitles: oldest down to 300") { keepNewest(it, 2, 300) },
        Trim(DsyncKey.Watchlist, "watchlist: oldest down to 150") { keepNewest(it, 2, 150) },
        Trim(DsyncKey.HiddenTitles, "hiddenTitles: oldest down to 150") { keepNewest(it, 2, 150) },
        Trim(DsyncKey.Favorites, "favorites: down to 500") { d ->
            val ids = d["ids"] as? kotlinx.serialization.json.JsonArray ?: return@Trim d
            d.withMember("ids", kotlinx.serialization.json.JsonArray(ids.take(500)))
        },
    )

    /** Apply trim [step] (index into [TRIMS]) to [value]; null when that category is absent (skip to the next step). */
    fun applyTrim(value: JsonObject, step: Int): JsonObject? {
        val t = TRIMS.getOrNull(step) ?: return null
        val cats = value[CATEGORIES] as? JsonObject ?: return null
        val sub = cats[t.key.suffix] as? JsonObject ?: return null
        val data = sub["data"] as? JsonObject ?: return null
        val newSub = sub.withMember("data", t.apply(data))
        return value.withMember(CATEGORIES, cats.withMember(t.key.suffix, newSub))
    }

    /** A category from a v1 envelope as a v3 sub-object (keeps updatedAt, deviceId, data, extra). */
    fun subFromV1(e: DsyncEnvelope): JsonObject {
        val sub = LinkedHashMap<String, JsonElement>()
        sub["updatedAt"] = JsonPrimitive(DsyncEnvelopeCodec.isoSeconds(e.updatedAt))
        sub["deviceId"] = JsonPrimitive(e.deviceId)
        sub["data"] = e.data
        e.extra?.let { sub["extra"] = it }
        return JsonObject(sub)
    }

    /**
     * Migration to the single key: every category from the v1 keys ([v1]),
     * the v2 keys ([v2], decoded buckets), and an existing v3 value ([v3])
     * merges per category by updatedAt (ties: higher deviceId), newest wins.
     * Null when there is nothing to migrate.
     */
    fun migrate(
        v1: Map<DsyncKey, DsyncEnvelope>,
        v2: List<Bucket>,
        v3: JsonObject?,
        deviceId: String,
        appVersion: String,
        now: Long,
    ): JsonObject? {
        if (v1.isEmpty() && v2.isEmpty()) return null
        val best = LinkedHashMap<String, Pair<DsyncEnvelope, JsonObject>>()
        fun offer(name: String, e: DsyncEnvelope, sub: JsonObject) {
            val cur = best[name]
            if (cur == null || DsyncEnvelopeCodec.remoteWins(cur.first.updatedAt, cur.first.deviceId, e.updatedAt, e.deviceId)) best[name] = e to sub
        }
        v1.forEach { (k, e) -> offer(k.suffix, e, subFromV1(e)) }
        v2.forEach { b ->
            DsyncKey.entries.forEach { k -> (category(b, k) as? DsyncDecode.Ok)?.envelope?.let { offer(k.suffix, it, it.raw) } }
        }
        val v3b = v3?.let { (decode(it) as? Decode.Ok)?.bucket }
        if (v3b != null) DsyncKey.entries.forEach { k -> (category(v3b, k) as? DsyncDecode.Ok)?.envelope?.let { offer(k.suffix, it, it.raw) } }
        val replace = LinkedHashMap<DsyncKey, JsonObject>()
        best.forEach { (name, p) -> DsyncKey.entries.firstOrNull { it.suffix == name }?.let { replace[it] = p.second } }
        return encodeBucket(v3, deviceId, appVersion, now, replace)
    }
}
