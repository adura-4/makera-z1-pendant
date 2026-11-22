package com.z1pendant.files

import com.z1pendant.link.ConnState
import com.z1pendant.link.MachineLink
import com.z1pendant.link.RemoteEntry
import com.z1pendant.platform.Prefs
import com.z1pendant.platform.nowMs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The Files screen: browse /sd/gcodes and its folders, Job info, rename, delete, new folder, upload from
 * the phone. Everything here is answered by the Z1's Wi-Fi board; nothing moves the machine.
 *
 * Rules: nothing that writes to the SD card (upload, delete, rename, new folder) while a job is running,
 * paused or changing tools, and never the file that is playing.
 */
class FilesController(
    private val link: MachineLink,
    private val scope: CoroutineScope,
    private val prefs: Prefs,
    private val notify: (String) -> Unit,
) {
    val path = MutableStateFlow(RemoteFiles.ROOT)
    /** The open folder's entries as listed (unsorted); null until the first listing arrives. */
    val entries = MutableStateFlow<List<RemoteEntry>?>(null)
    val loading = MutableStateFlow(false)
    val loadError = MutableStateFlow<String?>(null)
    val sort = MutableStateFlow(RemoteFiles.Sort.entries.firstOrNull { it.name == prefs.getString(PREF_SORT, null) } ?: RemoteFiles.Sort.Newest)
    /** Names selected in the open folder (hold to select). */
    val selected = MutableStateFlow<Set<String>>(emptySet())
    /** An upload in progress. */
    val upload = MutableStateFlow<Upload?>(null)
    /** An upload waiting for "replace / keep both / cancel" because the name is taken. */
    val conflict = MutableStateFlow<Pending?>(null)
    /** A slow operation in progress ("Deleting 3 files…"), shown instead of the list's footer. */
    val working = MutableStateFlow<String?>(null)
    /** Names uploaded since the folder was opened (tagged "just uploaded"). */
    val justUploaded = MutableStateFlow<Set<String>>(emptySet())
    /** Job info by full path. */
    val jobInfo = MutableStateFlow<Map<String, InfoState>>(emptyMap())

    /** [note]: what it's waiting for before the first packet goes ("Waiting for the Z1…", "Starting…"); null once sending. */
    data class Upload(val name: String, val sent: Long, val total: Long, val startedAt: Long, val note: String? = null)
    class Pending(val name: String, val bytes: ByteArray)
    sealed interface InfoState {
        data object Loading : InfoState
        data class Ready(val info: JobInfo, val seconds: Double) : InfoState
        data class Failed(val why: String) : InfoState
    }
    enum class Clash { Replace, KeepBoth, Cancel }

    /** Why the SD card can't be changed right now, or null when it can. */
    val readOnly: StateFlow<String?> = combine(link.conn, link.status) { c, s ->
        when {
            c != ConnState.Connected -> "Not connected to the Z1."
            s == null -> null
            s.job != null || s.state.lowercase() in BUSY_STATES -> "The Z1 is running a job. Files are read-only until it's idle."
            else -> null
        }
    }.stateIn(scope, SharingStarted.Eagerly, "Not connected to the Z1.")

    /** The playing file's full path, if any (it can't be renamed or deleted, even when idle in between). */
    val playing: StateFlow<String?> = link.jobFile.let { jf ->
        combine(jf, link.status) { f, s -> f?.path?.takeIf { s?.job != null } }.stateIn(scope, SharingStarted.Eagerly, null)
    }

    private var cancelUploadRequested = false

    fun open() {
        link.queryJobFile()
        refresh()
    }

    fun refresh() {
        val p = path.value
        scope.launch {
            loading.value = true
            val list = link.listDir(p)
            if (path.value != p) return@launch               // moved on meanwhile
            loading.value = false
            if (list == null) {
                loadError.value = if (link.conn.value != ConnState.Connected) "Not connected to the Z1." else "Couldn't read the folder."
                return@launch
            }
            loadError.value = null
            entries.value = list
            selected.value = selected.value.filter { n -> list.any { it.name == n } }.toSet()
        }
    }

    fun enter(name: String) = goTo(RemoteFiles.child(path.value, name))

    fun goTo(p: String) {
        if (p == path.value) return refresh()
        path.value = p
        entries.value = null
        selected.value = emptySet()
        justUploaded.value = emptySet()
        refresh()
    }

    /** Back: leave selection, then go up a folder. Returns false when there's nothing to go back from. */
    fun back(): Boolean = when {
        selected.value.isNotEmpty() -> { selected.value = emptySet(); true }
        path.value != RemoteFiles.ROOT -> { goTo(RemoteFiles.parent(path.value)); true }
        else -> false
    }

    fun setSort(s: RemoteFiles.Sort) {
        sort.value = s
        prefs.edit { putString(PREF_SORT, s.name) }
    }

    fun toggleSelected(name: String) {
        selected.value = if (name in selected.value) selected.value - name else selected.value + name
    }

    fun clearSelection() { selected.value = emptySet() }

    private fun canWrite(): Boolean {
        readOnly.value?.let { notify(it); return false }
        if (working.value != null || upload.value != null) { notify("Wait for the current file operation to finish."); return false }
        return true
    }

    private fun isPlaying(fullPath: String): Boolean {
        val p = playing.value ?: return false
        return p == fullPath || p.startsWith("$fullPath/")
    }

    // ---- delete ----
    fun delete(names: Collection<String>) {
        if (names.isEmpty() || !canWrite()) return
        val dir = path.value
        names.firstOrNull { isPlaying(RemoteFiles.child(dir, it)) }?.let { notify("$it is the file the Z1 is playing."); return }
        scope.launch {
            working.value = if (names.size == 1) "Deleting ${names.first()}…" else "Deleting ${names.size} items…"
            val failed = ArrayList<String>()
            for (n in names) link.delete(RemoteFiles.child(dir, n))?.let { failed += "$n: $it" }
            working.value = null
            selected.value = emptySet()
            notify(when {
                failed.isEmpty() -> if (names.size == 1) "Deleted ${names.first()}" else "Deleted ${names.size} items"
                else -> "Couldn't delete ${failed.joinToString("; ")}"
            })
            refresh()
        }
    }

    // ---- rename ----
    /** Rename [entry] to [newName] (cleaned: spaces become _). */
    fun rename(entry: RemoteEntry, newName: String) {
        val clean = RemoteFiles.cleanName(newName)
        if (clean.isEmpty() || clean == entry.name || !canWrite()) return
        val dir = path.value
        if (isPlaying(RemoteFiles.child(dir, entry.name))) { notify("${entry.name} is the file the Z1 is playing."); return }
        if (entries.value.orEmpty().any { it.name.equals(clean, ignoreCase = true) && it.name != entry.name }) {
            notify("$clean is already in this folder."); return
        }
        scope.launch {
            working.value = "Renaming…"
            val err = link.rename(RemoteFiles.child(dir, entry.name), RemoteFiles.child(dir, clean))
            working.value = null
            notify(err?.let { "Couldn't rename: $it" } ?: "Renamed to $clean")
            refresh()
        }
    }

    // ---- new folder ----
    fun makeFolder(name: String) {
        val clean = RemoteFiles.cleanName(name)
        if (clean.isEmpty() || !canWrite()) return
        if (entries.value.orEmpty().any { it.name.equals(clean, ignoreCase = true) }) { notify("$clean is already in this folder."); return }
        val dir = path.value
        scope.launch {
            working.value = "Creating $clean…"
            val err = link.makeDir(RemoteFiles.child(dir, clean))
            working.value = null
            notify(err?.let { "Couldn't create the folder: $it" } ?: "Created $clean")
            refresh()
        }
    }

    // ---- upload ----
    /**
     * The upload in progress, from the moment a file is picked (the reconnect wait, the start handshake,
     * the busy retries and the transfer): [cancelUpload] cancels it at any of those steps.
     */
    private var uploadJob: kotlinx.coroutines.Job? = null

    /** A file picked on the phone: upload it to the open folder (asks first if the name is taken). */
    fun upload(name: String, bytes: ByteArray) {
        if (upload.value != null) { notify("An upload is already running."); return }
        // The phone's picker puts the pendant in the background, where Android throttles its network: the
        // link often comes back "lost" and reconnects just as the file arrives (seen on a Pixel 10). Wait for
        // it, with the upload strip (and its Cancel) already showing.
        if (link.conn.value != ConnState.Connected) {
            upload.value = Upload(RemoteFiles.cleanName(name).ifEmpty { "file.nc" }, 0, bytes.size.toLong(), nowMs(), "Waiting for the Z1…")
            uploadJob = scope.launch {
                val back = kotlinx.coroutines.withTimeoutOrNull(10_000) {
                    link.conn.first { it == ConnState.Connected }
                    while (link.status.value == null) kotlinx.coroutines.delay(50)
                } != null
                upload.value = null
                if (back) { kotlinx.coroutines.delay(300); uploadNow(name, bytes) } else notify("Not connected to the Z1.")
            }
            return
        }
        uploadNow(name, bytes)
    }

    private fun uploadNow(name: String, bytes: ByteArray) {
        if (!canWrite()) return
        if (bytes.size > MAX_UPLOAD) { notify("That file is too big (${RemoteFiles.sizeLabel(bytes.size.toLong())})."); return }
        val clean = RemoteFiles.cleanName(name).ifEmpty { "file.nc" }
        if (entries.value.orEmpty().any { !it.isDir && it.name == clean }) { conflict.value = Pending(clean, bytes); return }
        startUpload(clean, bytes)
    }

    fun resolveConflict(choice: Clash) {
        val p = conflict.value ?: return
        conflict.value = null
        when (choice) {
            Clash.Replace -> if (isPlaying(RemoteFiles.child(path.value, p.name))) notify("${p.name} is the file the Z1 is playing.")
                             else startUpload(p.name, p.bytes)
            Clash.KeepBoth -> startUpload(RemoteFiles.uniqueName(p.name, entries.value.orEmpty().map { it.name }.toSet()), p.bytes)
            Clash.Cancel -> {}
        }
    }

    private fun startUpload(name: String, bytes: ByteArray) {
        val dir = path.value
        cancelUploadRequested = false
        upload.value = Upload(name, 0, bytes.size.toLong(), nowMs(), "Starting…")
        uploadJob = scope.launch {
            val started = upload.value?.startedAt ?: nowMs()
            val err = link.uploadFile(RemoteFiles.child(dir, name), bytes) { sent, total ->
                upload.value = Upload(name, sent, total, started)
                !cancelUploadRequested
            }
            upload.value = null
            when (err) {
                null -> { notify("Uploaded $name"); if (path.value == dir) justUploaded.value = justUploaded.value + name }
                "cancelled" -> notify("Upload cancelled")
                else -> notify("Upload failed: $err")
            }
            if (path.value == dir) refresh()
        }
    }

    /**
     * Cancel at any step. The link tells the Wi-Fi board to drop the transfer (0xB5), and the board
     * deletes the half-written file itself, so nothing partial is left on the SD card.
     */
    fun cancelUpload() {
        cancelUploadRequested = true
        val j = uploadJob ?: return
        if (upload.value == null) return
        uploadJob = null
        j.cancel()
        upload.value = null
        notify("Upload cancelled")
        scope.launch { j.join(); refresh() }
    }

    // ---- job info ----
    /** Read the file's header (the first few KB) for its stock, zero and tools. */
    fun loadJobInfo(entry: RemoteEntry) {
        val full = RemoteFiles.child(path.value, entry.name)
        if (jobInfo.value[full] is InfoState.Loading) return
        jobInfo.value = jobInfo.value + (full to InfoState.Loading)
        scope.launch {
            val t0 = nowMs()
            val head = link.readFileHead(full, HEADER_BYTES) { JobInfo.headerComplete(it) }
            val state = if (head == null) InfoState.Failed("Couldn't read the file.")
                        else InfoState.Ready(JobInfo.parse(head), (nowMs() - t0) / 1000.0)
            jobInfo.value = jobInfo.value + (full to state)
        }
    }

    companion object {
        private const val PREF_SORT = "filesSort"
        private val BUSY_STATES = setOf("run", "hold", "pause", "tool", "home", "door")
        /** Header reads stop here at the latest. */
        const val HEADER_BYTES = 64 * 1024
        /** Uploads above this are refused (the Z1's SD card is fine with more; the phone's memory may not be). */
        const val MAX_UPLOAD = 64 * 1024 * 1024
    }
}
