package com.aeriotv.android.core.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Android#114 (Formuler GTV-BT1 and similar remotes): buttons the fixed slot
 * map does not know (color keys, F-keys, Guide, Info, TV, Last Channel and
 * so on) can be taught in Settings > Remote Control > Learn Button. Each
 * learned key carries one action while watching and one in the guide, chosen
 * from the same action lists the fixed slots use.
 *
 * Stored on its own preference key (remote_learned_buttons) so the shared
 * cross-platform slot schema in [RemoteControlMap] is untouched.
 */
data class LearnedButton(
    val keyCode: Int,
    val player: PlayerRemoteAction = PlayerRemoteAction.NONE,
    val guide: GuideRemoteAction = GuideRemoteAction.NONE,
)

object LearnedButtons {
    /** Live copy for MainActivity and the guide grid (key dispatch cannot
     *  suspend). Written by MainActivity from AppPreferences. */
    @Volatile var current: Map<Int, LearnedButton> = emptyMap()

    /** Set while the Learn Button dialog is waiting for a press. MainActivity
     *  hands it the next learnable key DOWN and consumes the event. */
    @Volatile var onCapture: ((Int) -> Unit)? = null

    // android.view.KeyEvent values, inlined so this file stays pure JVM and
    // unit-testable.
    private const val DPAD_UP = 19
    private const val DPAD_DOWN = 20
    private const val DPAD_LEFT = 21
    private const val DPAD_RIGHT = 22
    private const val DPAD_CENTER = 23
    private const val BACK = 4
    private const val HOME = 3
    private const val ENTER = 66
    private const val NUMPAD_ENTER = 160
    private const val POWER = 26
    private const val SLEEP = 223
    private const val WAKEUP = 224
    private const val VOLUME_UP = 24
    private const val VOLUME_DOWN = 25
    private const val VOLUME_MUTE = 164
    private const val MUTE = 91
    private const val MEDIA_PLAY_PAUSE = 85
    private const val MEDIA_PLAY = 126
    private const val MEDIA_FAST_FORWARD = 90
    private const val MEDIA_REWIND = 89
    private const val CHANNEL_UP = 166
    private const val CHANNEL_DOWN = 167
    private const val SEARCH = 84
    private const val ASSIST = 219
    private const val VOICE_ASSIST = 231
    private const val SETTINGS = 176
    private const val APP_SWITCH = 187
    private const val TV_POWER = 177
    private const val TV_INPUT = 178
    private const val NUM_0 = 7
    private const val NUM_9 = 16
    private const val NUMPAD_0 = 144
    private const val NUMPAD_9 = 153

    /** Keys that already have a fixed meaning (navigation, Back, the slot
     *  keys, digits for channel number entry) or belong to the system. */
    private val RESERVED: Set<Int> = setOf(
        DPAD_UP, DPAD_DOWN, DPAD_LEFT, DPAD_RIGHT, DPAD_CENTER, BACK, HOME,
        ENTER, NUMPAD_ENTER, POWER, SLEEP, WAKEUP, VOLUME_UP, VOLUME_DOWN,
        VOLUME_MUTE, MUTE, MEDIA_PLAY_PAUSE, MEDIA_PLAY, MEDIA_FAST_FORWARD,
        MEDIA_REWIND, CHANNEL_UP, CHANNEL_DOWN, SEARCH, ASSIST, VOICE_ASSIST,
        SETTINGS, APP_SWITCH, TV_POWER, TV_INPUT,
    )

    fun isLearnable(keyCode: Int): Boolean =
        keyCode > 0 && keyCode !in RESERVED &&
            keyCode !in NUM_0..NUM_9 && keyCode !in NUMPAD_0..NUMPAD_9

    private val FRIENDLY: Map<String, String> = mapOf(
        "PROG_RED" to "Red",
        "PROG_GREEN" to "Green",
        "PROG_YELLOW" to "Yellow",
        "PROG_BLUE" to "Blue",
        "GUIDE" to "Guide",
        "INFO" to "Info",
        "TV" to "TV",
        "LAST_CHANNEL" to "Last Channel",
        "MENU" to "Menu",
        "BOOKMARK" to "Bookmark",
        "CAPTIONS" to "Captions",
        "DVR" to "DVR",
        "MEDIA_RECORD" to "Record",
        "MEDIA_STOP" to "Stop",
        "MEDIA_NEXT" to "Next",
        "MEDIA_PREVIOUS" to "Previous",
        "MEDIA_PAUSE" to "Pause",
        "MEDIA_AUDIO_TRACK" to "Audio Track",
        "MEDIA_SKIP_FORWARD" to "Skip Forward",
        "MEDIA_SKIP_BACKWARD" to "Skip Backward",
        "MEDIA_STEP_FORWARD" to "Step Forward",
        "MEDIA_STEP_BACKWARD" to "Step Backward",
        "MEDIA_TOP_MENU" to "Top Menu",
        "TV_ZOOM_MODE" to "Zoom",
        "TV_TELETEXT" to "Teletext",
        "TV_AUDIO_DESCRIPTION" to "Audio Description",
        "WINDOW" to "Window",
        "PAGE_UP" to "Page Up",
        "PAGE_DOWN" to "Page Down",
    )

    /**
     * Readable name from android.view.KeyEvent.keyCodeToString output
     * ("KEYCODE_PROG_RED" -> "Red", "KEYCODE_F5" -> "F5",
     * "KEYCODE_BUTTON_A" -> "Button A", a bare number -> "Button 287").
     */
    fun displayName(keyCodeString: String, keyCode: Int): String {
        val raw = keyCodeString.removePrefix("KEYCODE_")
        if (raw.isEmpty() || raw.all { it.isDigit() }) return "Button $keyCode"
        FRIENDLY[raw]?.let { return it }
        if (Regex("F\\d{1,2}").matches(raw)) return raw
        return raw.split('_').filter { it.isNotEmpty() }.joinToString(" ") { part ->
            if (part.length <= 2) part else part.lowercase().replaceFirstChar { it.uppercase() }
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    fun toJson(buttons: Collection<LearnedButton>): String = buildJsonArray {
        buttons.sortedBy { it.keyCode }.forEach { b ->
            add(
                buildJsonObject {
                    put("keyCode", b.keyCode)
                    put("player", b.player.wire)
                    put("guide", b.guide.wire)
                },
            )
        }
    }.toString()

    /** Tolerant decode: malformed rows and unknown actions are skipped or
     *  fall back to NONE. Never throws. */
    fun fromJson(raw: String?): Map<Int, LearnedButton> {
        if (raw.isNullOrBlank()) return emptyMap()
        val arr = runCatching { json.parseToJsonElement(raw) as? JsonArray }.getOrNull() ?: return emptyMap()
        val out = linkedMapOf<Int, LearnedButton>()
        for (el in arr) {
            val o = el as? JsonObject ?: continue
            val code = runCatching { o["keyCode"]?.jsonPrimitive?.intOrNull }.getOrNull() ?: continue
            if (!isLearnable(code)) continue
            val player = runCatching { o["player"]?.jsonPrimitive?.content }.getOrNull()
                ?.let(PlayerRemoteAction::fromWire) ?: PlayerRemoteAction.NONE
            val guide = runCatching { o["guide"]?.jsonPrimitive?.content }.getOrNull()
                ?.let(GuideRemoteAction::fromWire) ?: GuideRemoteAction.NONE
            out[code] = LearnedButton(code, player, guide)
        }
        return out
    }
}
