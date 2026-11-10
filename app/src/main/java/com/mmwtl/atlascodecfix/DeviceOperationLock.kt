package com.mmwtl.atlascodecfix

import kotlinx.coroutines.sync.Mutex

/**
 * Process-wide lock for every device-side operation sequence (codec staging/apply/detect and AOT
 * status/compile). Holding one lock for all features keeps two ADB sequences from interleaving.
 */
internal object DeviceOperationLock {
    val mutex = Mutex()
}
