package com.aeriotv.android.core.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * TV remote button mapping (task #190 initiative, plan:
 * ~/Desktop/AerioTV-Remote-Control-Plan.md). One map per CONTEXT
 * (player vs guide), shared JSON schema with the
 * Apple platforms:
 *
 * ```json
 * { "version": 1, "preset": "aerioDefault",
 *   "player": { "okLong": "optionsMenu", ... },
 *   "guide":  { "ffwd": "pageDown", ... } }
 * ```
 *
 * Design invariants (do not relax without a plan change):
 * - Back is a slot in the GUIDE map only (backShort / backLong, Logan
 *   2026-10-15). The player's Back, the guide's double-Back rules (top
 *   channel with a mini, stop Radio Mode), the Radio Mode single-Back
 *   deferral, and the hold-Back Exit AerioTV? prompt stay hardcoded.
 * - One press (key plus short or hold) holds exactly one action per
 *   context; a stored blob that repeats a press keeps the first.
 * - Guide SHORT Left/Right default to focus navigation ([GuideRemoteAction.NAVIGATE]);
 *   a remapped short arrow still navigates until focus reaches the edge
 *   of the timeline (see GuideGrid), so the grid can never trap focus.
 * - Unknown slots/actions in a stored blob are IGNORED on read
 *   (forward compat with newer app versions); missing slots fall back
 *   to [RemoteControlMap.DEFAULT]'s assignment at RESOLVE time, so a
 *   partial blob never strands a button.
 */

/** Stable wire ids. Which slots a platform/context actually offers is
 *  decided by the settings UI + resolver, not by this enum. */
enum class RemoteSlot(val wire: String) {
    OK_SHORT("okShort"), OK_LONG("okLong"),
    UP_SHORT("upShort"), UP_LONG("upLong"),
    DOWN_SHORT("downShort"), DOWN_LONG("downLong"),
    LEFT_SHORT("leftShort"), LEFT_LONG("leftLong"),
    RIGHT_SHORT("rightShort"), RIGHT_LONG("rightLong"),
    PLAY_PAUSE("playPause"), FFWD("ffwd"), REWIND("rewind"),
    CHANNEL_UP("channelUp"), CHANNEL_DOWN("channelDown"),
    /** Guide map only: a single Back press and a Back hold. */
    BACK_SHORT("backShort"), BACK_LONG("backLong");

    companion object {
        fun fromWire(s: String): RemoteSlot? = entries.firstOrNull { it.wire == s }
    }
}

/** Actions available while the fullscreen live player is frontmost. */
enum class PlayerRemoteAction(val wire: String) {
    CHANNEL_UP("channelUp"), CHANNEL_DOWN("channelDown"),
    LAST_CHANNEL("lastChannel"),
    RECENT_CHANNELS("recentChannels"),
    TOGGLE_CONTROLS("toggleControls"),
    SHOW_PROGRAM_INFO("showProgramInfo"),
    OPTIONS_MENU("optionsMenu"),
    PLAY_PAUSE("playPause"),
    SEEK_FORWARD("seekForward"), SEEK_BACKWARD("seekBackward"),
    RESTART_PROGRAM("restartProgram"), JUMP_TO_LIVE("jumpToLive"),
    MINIMIZE_TO_GUIDE("minimizeToGuide"),
    CHANNEL_LIST("channelList"),
    SUBTITLES("subtitles"), AUDIO_TRACKS("audioTracks"),
    ASPECT_RATIO("aspectRatio"), RECORD("record"),
    SLEEP_TIMER("sleepTimer"), OPEN_SEARCH("openSearch"),
    /** Fixed hold-Back behavior (stop with NO mini promotion); dispatched
     *  by MainActivity's long-Back path, deliberately NOT offered as a
     *  mappable choice in settings (Back semantics stay hardcoded). */
    STOP_PLAYBACK("stopPlayback"),
    NONE("none");

    companion object {
        fun fromWire(s: String): PlayerRemoteAction? = entries.firstOrNull { it.wire == s }
    }
}

/** Actions available while the TV guide is frontmost. */
enum class GuideRemoteAction(val wire: String) {
    PAGE_UP("pageUp"), PAGE_DOWN("pageDown"),
    TIMELINE_BACK("timelineBack"), TIMELINE_FORWARD("timelineForward"),
    JUMP_TO_NOW("jumpToNow"), JUMP_TO_TOP("jumpToTop"),
    JUMP_TO_DAY("jumpToDay"),
    FOCUS_GROUP_PILLS("focusGroupPills"),
    RESUME_PLAYER("resumePlayer"), CLOSE_MINI_PLAYER("closeMiniPlayer"),
    PROGRAM_INFO("programInfo"), OPEN_SEARCH("openSearch"),
    /** The program options menu (the guide's long-press Select menu). */
    PROGRAM_MENU("programMenu"),
    /** Tune the focused channel (the guide's Select). */
    PLAY("play"),
    /** Open the record sheet for the focused program. */
    RECORD("record"),
    /** Ordinary D-pad focus movement (short Left/Right default). */
    NAVIGATE("navigate"),
    /** The guide's built-in Back ladder: back to now and the top channel,
     *  then the Exit AerioTV? prompt. Default for [RemoteSlot.BACK_SHORT]. */
    BACK_LADDER("backLadder"),
    NONE("none");

    companion object {
        fun fromWire(s: String): GuideRemoteAction? = entries.firstOrNull { it.wire == s }
    }
}

enum class RemotePreset(val wire: String) {
    DEFAULT("default"), CUSTOM("custom");

    companion object {
        /** Only wires this build has ever written are honored. Anything
         *  else (notably the pre-release preset-picker blobs) is null and
         *  the whole stored map is DISCARDED at decode - those betas'
         *  slot layouts no longer match the shipped scheme, so keeping
         *  them as CUSTOM stranded users on stale mappings. */
        fun fromWire(s: String): RemotePreset? =
            entries.firstOrNull { it.wire == s }
    }
}

data class RemoteControlMap(
    val preset: RemotePreset = RemotePreset.DEFAULT,
    val player: Map<RemoteSlot, PlayerRemoteAction> = emptyMap(),
    val guide: Map<RemoteSlot, GuideRemoteAction> = emptyMap(),
) {
    /** Resolve with fallback to the default map so a partial/legacy blob
     *  never leaves a button dead. Explicit NONE is respected. */
    fun playerAction(slot: RemoteSlot): PlayerRemoteAction =
        player[slot] ?: DEFAULT.player[slot] ?: PlayerRemoteAction.NONE

    fun guideAction(slot: RemoteSlot): GuideRemoteAction = guideAction(slot, sidebarGroups = false)

    /**
     * Guide resolve that knows the group selector: an unset Left (Hold)
     * opens the group sidebar in sidebar mode and browses earlier programs
     * with the top pills, which is exactly what the guide did before the
     * slot was offered (it is the one default that depends on a setting).
     */
    fun guideAction(slot: RemoteSlot, sidebarGroups: Boolean): GuideRemoteAction =
        guide[slot] ?: if (slot == RemoteSlot.LEFT_LONG) {
            if (sidebarGroups) GuideRemoteAction.FOCUS_GROUP_PILLS else GuideRemoteAction.TIMELINE_BACK
        } else {
            DEFAULT.guide[slot] ?: GuideRemoteAction.NONE
        }

    /** First slot currently mapped to [action], for dynamic hint copy. */
    fun playerSlotFor(action: PlayerRemoteAction): RemoteSlot? =
        RemoteSlot.entries.firstOrNull { playerAction(it) == action }

    fun guideSlotFor(action: GuideRemoteAction): RemoteSlot? =
        RemoteSlot.entries.firstOrNull { guideAction(it) == action }

    fun toJson(): String {
        val obj = buildJsonObject {
            put("version", SCHEMA_VERSION)
            put("preset", preset.wire)
            put(GUIDE_KEYS_FIELD, GUIDE_KEYS_VERSION)
            put("player", buildJsonObject {
                player.forEach { (slot, action) -> put(slot.wire, action.wire) }
            })
            put("guide", buildJsonObject {
                guide.forEach { (slot, action) -> put(slot.wire, action.wire) }
            })
        }
        return obj.toString()
    }

    companion object {
        const val SCHEMA_VERSION = 1

        /** Marks a blob written after the guide Select/Left/Right slots
         *  became honored. Older CUSTOM blobs carry full slot dumps whose
         *  okLong / leftLong values the guide never read; see [fromJson]. */
        private const val GUIDE_KEYS_FIELD = "guideKeys"
        private const val GUIDE_KEYS_VERSION = 2

        /** The PRE-initiative control scheme, kept verbatim so any
         *  regression can be reverted by pointing DEFAULT back at it.
         *  NOT user-selectable (Logan 2026-07-20): the redesigned
         *  scheme below is the app's one standard layout now. */
        val LEGACY_SCHEME = RemoteControlMap(
            preset = RemotePreset.DEFAULT,
            player = mapOf(
                RemoteSlot.UP_SHORT to PlayerRemoteAction.CHANNEL_UP,
                RemoteSlot.DOWN_SHORT to PlayerRemoteAction.CHANNEL_DOWN,
                RemoteSlot.LEFT_SHORT to PlayerRemoteAction.SEEK_BACKWARD,
                RemoteSlot.RIGHT_SHORT to PlayerRemoteAction.SEEK_FORWARD,
                RemoteSlot.OK_SHORT to PlayerRemoteAction.TOGGLE_CONTROLS,
                RemoteSlot.OK_LONG to PlayerRemoteAction.NONE,
                RemoteSlot.PLAY_PAUSE to PlayerRemoteAction.PLAY_PAUSE,
                RemoteSlot.FFWD to PlayerRemoteAction.SEEK_FORWARD,
                RemoteSlot.REWIND to PlayerRemoteAction.SEEK_BACKWARD,
                RemoteSlot.CHANNEL_UP to PlayerRemoteAction.CHANNEL_UP,
                RemoteSlot.CHANNEL_DOWN to PlayerRemoteAction.CHANNEL_DOWN,
            ),
            guide = mapOf(
                RemoteSlot.LEFT_LONG to GuideRemoteAction.TIMELINE_BACK,
                RemoteSlot.RIGHT_LONG to GuideRemoteAction.CLOSE_MINI_PLAYER,
                RemoteSlot.OK_LONG to GuideRemoteAction.PROGRAM_INFO,
                RemoteSlot.PLAY_PAUSE to GuideRemoteAction.RESUME_PLAYER,
            ),
        )

        /** The app's standard control scheme (Logan 2026-07-20 revision:
         *  OK went back to surfacing the player controls, and hold-OK is
         *  unmapped - options live on the chrome's Options button): OK =
         *  show controls, Up/Down = channel surf, hold-Up = recently
         *  watched, hold-Down = search, Right = previous-channel zap,
         *  hold-Right = program info. In the guide, Select plays, hold-Select
         *  opens the program menu, hold-Left opens the group menu (sidebar
         *  mode) or browses earlier programs (top pills). */
        val DEFAULT = RemoteControlMap(
            preset = RemotePreset.DEFAULT,
            player = mapOf(
                RemoteSlot.OK_SHORT to PlayerRemoteAction.TOGGLE_CONTROLS,
                RemoteSlot.OK_LONG to PlayerRemoteAction.NONE,
                RemoteSlot.UP_SHORT to PlayerRemoteAction.CHANNEL_UP,
                RemoteSlot.DOWN_SHORT to PlayerRemoteAction.CHANNEL_DOWN,
                RemoteSlot.UP_LONG to PlayerRemoteAction.RECENT_CHANNELS,
                RemoteSlot.DOWN_LONG to PlayerRemoteAction.OPEN_SEARCH,
                RemoteSlot.LEFT_SHORT to PlayerRemoteAction.CHANNEL_LIST,
                RemoteSlot.LEFT_LONG to PlayerRemoteAction.MINIMIZE_TO_GUIDE,
                RemoteSlot.RIGHT_SHORT to PlayerRemoteAction.LAST_CHANNEL,
                RemoteSlot.RIGHT_LONG to PlayerRemoteAction.SHOW_PROGRAM_INFO,
                RemoteSlot.PLAY_PAUSE to PlayerRemoteAction.PLAY_PAUSE,
                RemoteSlot.FFWD to PlayerRemoteAction.SEEK_FORWARD,
                RemoteSlot.REWIND to PlayerRemoteAction.SEEK_BACKWARD,
                RemoteSlot.CHANNEL_UP to PlayerRemoteAction.CHANNEL_UP,
                RemoteSlot.CHANNEL_DOWN to PlayerRemoteAction.CHANNEL_DOWN,
            ),
            // LEFT_LONG is deliberately absent: its default depends on the
            // group selector and is resolved in guideAction(slot, sidebarGroups).
            guide = mapOf(
                RemoteSlot.FFWD to GuideRemoteAction.PAGE_DOWN,
                RemoteSlot.REWIND to GuideRemoteAction.PAGE_UP,
                RemoteSlot.CHANNEL_UP to GuideRemoteAction.PAGE_UP,
                RemoteSlot.CHANNEL_DOWN to GuideRemoteAction.PAGE_DOWN,
                RemoteSlot.OK_SHORT to GuideRemoteAction.PLAY,
                RemoteSlot.OK_LONG to GuideRemoteAction.PROGRAM_MENU,
                RemoteSlot.LEFT_SHORT to GuideRemoteAction.NAVIGATE,
                RemoteSlot.RIGHT_SHORT to GuideRemoteAction.NAVIGATE,
                RemoteSlot.RIGHT_LONG to GuideRemoteAction.CLOSE_MINI_PLAYER,
                RemoteSlot.PLAY_PAUSE to GuideRemoteAction.RESUME_PLAYER,
                RemoteSlot.BACK_SHORT to GuideRemoteAction.BACK_LADDER,
                RemoteSlot.BACK_LONG to GuideRemoteAction.NONE,
            ),
        )

        private val json = Json { ignoreUnknownKeys = true }

        /** Tolerant decode: unknown slots/actions are skipped, malformed
         *  input falls back to [DEFAULT]. Never throws. */
        fun fromJson(raw: String?): RemoteControlMap {
            if (raw.isNullOrBlank()) return DEFAULT
            return runCatching {
                val obj = json.parseToJsonElement(raw).jsonObject
                val preset = RemotePreset.fromWire(
                    obj["preset"]?.jsonPrimitive?.content ?: RemotePreset.DEFAULT.wire,
                ) ?: return DEFAULT
                // preset=default means "track the app's standard scheme":
                // ignore any stored slot dump (older builds persisted the
                // full resolved map, which would otherwise pin those users
                // to that build's defaults forever - e.g. holding upLong on
                // lastChannel after the scheme moved it to recentChannels).
                // Stored slots are only authoritative for CUSTOM.
                if (preset == RemotePreset.DEFAULT) return DEFAULT
                val firsts = firstPressValues(raw)
                var guide = decodeContext(obj["guide"] as? JsonObject, firsts["guide"]) { GuideRemoteAction.fromWire(it) }
                if (obj[GUIDE_KEYS_FIELD] == null) guide = migrateLegacyGuide(guide)
                RemoteControlMap(
                    preset = preset,
                    player = decodeContext(obj["player"] as? JsonObject, firsts["player"]) { PlayerRemoteAction.fromWire(it) }
                        // Back is never a player slot.
                        .filterKeys { it != RemoteSlot.BACK_SHORT && it != RemoteSlot.BACK_LONG },
                    guide = guide,
                )
            }.getOrDefault(DEFAULT)
        }

        /**
         * Pre-guideKeys blobs: the guide ignored okLong (always the program
         * menu) and, in sidebar mode, leftLong (always the sidebar); a
         * leftLong of NONE fell back to the group menu. Drop or rewrite those
         * values so the saved map keeps doing what it did on device.
         */
        internal fun migrateLegacyGuide(guide: Map<RemoteSlot, GuideRemoteAction>): Map<RemoteSlot, GuideRemoteAction> {
            val out = guide.toMutableMap()
            out.remove(RemoteSlot.OK_SHORT)
            out.remove(RemoteSlot.OK_LONG)
            out.remove(RemoteSlot.LEFT_SHORT)
            out.remove(RemoteSlot.RIGHT_SHORT)
            when (out[RemoteSlot.LEFT_LONG]) {
                GuideRemoteAction.TIMELINE_BACK -> out.remove(RemoteSlot.LEFT_LONG)
                GuideRemoteAction.NONE -> out[RemoteSlot.LEFT_LONG] = GuideRemoteAction.FOCUS_GROUP_PILLS
                else -> Unit
            }
            return out
        }

        /** Log sink for decode notes; swapped in unit tests (no android.util.Log there). */
        internal var log: (String) -> Unit = { msg ->
            runCatching { android.util.Log.w("AerioTV", msg) }
        }

        private val contextRegex = Regex("\"(player|guide)\"\\s*:\\s*\\{([^{}]*)\\}")
        private val pairRegex = Regex("\"([^\"]+)\"\\s*:\\s*\"([^\"]*)\"")

        /**
         * Conflict rule (Logan 2026-10-15): one press holds exactly one action
         * per context. A JSON parser keeps the LAST of repeated keys, so the
         * raw blob is scanned for the FIRST value of each press; later
         * duplicates are dropped and logged. Returns context -> (press -> value).
         */
        internal fun firstPressValues(raw: String): Map<String, Map<String, String>> {
            val out = mutableMapOf<String, Map<String, String>>()
            for (ctx in contextRegex.findAll(raw)) {
                val firsts = linkedMapOf<String, String>()
                for (pair in pairRegex.findAll(ctx.groupValues[2])) {
                    val press = pair.groupValues[1]
                    if (press in firsts) {
                        log("[REMOTE] duplicate action for $press dropped")
                    } else {
                        firsts[press] = pair.groupValues[2]
                    }
                }
                out[ctx.groupValues[1]] = firsts
            }
            return out
        }

        private fun <A : Any> decodeContext(
            obj: JsonObject?,
            firsts: Map<String, String>?,
            action: (String) -> A?,
        ): Map<RemoteSlot, A> {
            if (obj == null) return emptyMap()
            val out = mutableMapOf<RemoteSlot, A>()
            for ((key, value) in obj) {
                val slot = RemoteSlot.fromWire(key) ?: continue
                val wire = firsts?.get(key) ?: value.jsonPrimitive.content
                val act = runCatching { action(wire) }.getOrNull() ?: continue
                out[slot] = act
            }
            return out
        }
    }
}
