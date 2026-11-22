package com.z1pendant.files

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * A file another app shared to the pendant (Android: Share → Z1 Pendant). The platform shell reads the file
 * and puts it here; the app opens Files, which asks "Upload to the Z1?" and clears it on Upload or Cancel.
 * Process-wide because the share arrives through a separate activity, before the main one is in front.
 */
object ShareInbox {
    class Shared(val name: String, val bytes: ByteArray)

    val file = MutableStateFlow<Shared?>(null)
}
