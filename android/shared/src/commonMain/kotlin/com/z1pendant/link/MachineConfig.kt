package com.z1pendant.link

import com.z1pendant.util.Fmt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val NUM = """([-+]?\d+(?:\.\d+)?)"""
private val getRe = Regex("""(\S+) is set to\s+$NUM""")
private val setRe = Regex("""(\S+) has been set to\s+$NUM""")
private val noSpaceRe = Regex("""(\S+) not enough space to overwrite""")

/** `config-get` reply: "sd: coordinate.anchor1_x is set to -190.69". A set reply never matches. */
fun parseGetReply(line: String): Pair<String, Double>? = parse(getRe, line)

/** `config-set` reply: "sd: coordinate.anchor1_x has been set to -190.61". A get reply never matches. */
fun parseSetReply(line: String): Pair<String, Double>? = parse(setRe, line)

/** The firmware's in-place config write failed: "sd: <key> not enough space to overwrite existing key/value". */
fun parseNoSpaceReply(line: String): String? = noSpaceRe.find(line)?.groupValues?.get(1)

/** Anchor 1's config keys. Declared here, with the code that reads and writes them. */
const val ANCHOR_X_KEY = "coordinate.anchor1_x"
const val ANCHOR_Y_KEY = "coordinate.anchor1_y"

/** Outcome of [MachineConfig.set]. */
sealed class SetResult {
    object Acknowledged : SetResult()
    /** The value is too long for the config line; retry with fewer decimals. */
    object NoSpace : SetResult()
    object TimedOut : SetResult()
}

private fun parse(re: Regex, line: String): Pair<String, Double>? {
    val r = re.find(line) ?: return null
    return r.groupValues[1] to (r.groupValues[2].toDoubleOrNull() ?: return null)
}

/** Reads and writes the machine's config.txt values over the link. Main thread only, like the link's callbacks. */
class MachineConfig(private val link: MachineLink, scope: CoroutineScope) {
    private val getWait = HashMap<String, CompletableDeferred<Double>>()
    private val setWait = HashMap<String, CompletableDeferred<SetResult>>()
    private val anchorState = MutableStateFlow<Pair<Double, Double>?>(null)

    /** Anchor 1 in machine X/Y once read; null after a disconnect or [invalidateAnchor]. */
    val anchorFlow: StateFlow<Pair<Double, Double>?> get() = anchorState

    init {
        scope.launch {
            link.messages.collect { m ->
                parseGetReply(m)?.let { (k, v) -> getWait[k]?.complete(v) }
                parseSetReply(m)?.let { (k, _) -> setWait[k]?.complete(SetResult.Acknowledged) }
                parseNoSpaceReply(m)?.let { k -> setWait[k]?.complete(SetResult.NoSpace) }
            }
        }
        scope.launch { link.conn.collect { if (it != ConnState.Connected) anchorState.value = null } }
    }

    /** `config-get sd <key>`; null if no reply within 4 s. */
    suspend fun get(key: String): Double? = ask(getWait, key, "config-get sd $key")

    /** `config-set sd <key> <value>`. [decimals] drops to 2 to retry a write the line has no room for. */
    suspend fun set(key: String, value: Double, decimals: Int = 3): SetResult {
        val anchorKey = key == ANCHOR_X_KEY || key == ANCHOR_Y_KEY
        if (anchorKey) invalidateAnchor()
        try {
            return ask(setWait, key, "config-set sd $key ${Fmt.fixed(value, decimals)}") ?: SetResult.TimedOut
        } finally {
            if (anchorKey) invalidateAnchor()   // a read during the write may have cached the old value
        }
    }

    /** Anchor 1 in machine X/Y, cached until a disconnect or [invalidateAnchor]. */
    suspend fun anchor(): Pair<Double, Double>? {
        anchorState.value?.let { return it }
        if (link.conn.value != ConnState.Connected) return null
        val x = get(ANCHOR_X_KEY) ?: return null
        val y = get(ANCHOR_Y_KEY) ?: return null
        return (x to y).also { anchorState.value = it }
    }

    fun invalidateAnchor() { anchorState.value = null }

    private suspend fun <T : Any> ask(wait: HashMap<String, CompletableDeferred<T>>, key: String, cmd: String): T? {
        val d = CompletableDeferred<T>()
        wait[key] = d
        try {
            link.sendLine(cmd)
            return withTimeoutOrNull(4000) { d.await() }
        } finally {
            wait.remove(key, d)   // two-argument form: never drops a newer caller's entry
        }
    }
}
