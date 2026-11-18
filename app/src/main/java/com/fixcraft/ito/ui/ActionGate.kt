package com.fixcraft.ito.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Admits one running operation per action key. A tap that arrives while the
 * same action still runs is dropped instead of queueing a second operation,
 * which for pairing would create another pairing later. Different keys never
 * wait for each other, so one slow chat cannot hold up another chat or a call.
 */
internal class ActionGate {
    private val running = MutableStateFlow<Set<String>>(emptySet())

    /** Keys whose operation is still running. Their controls show progress. */
    val busy: StateFlow<Set<String>> = running.asStateFlow()

    /** True when [key] was free and is now held. The caller must [release] it exactly once. */
    fun admit(key: String): Boolean {
        var admitted = false
        running.update { current ->
            admitted = key !in current
            if (admitted) current + key else current
        }
        return admitted
    }

    fun release(key: String) = running.update { it - key }
}

/** Action keys. A key names one thing a person can start, scoped as narrowly as the operation. */
internal object Actions {
    const val SHOW_CODE = "pair:code"
    const val MAKE_LINK = "pair:link"
    const val OPEN_LINK = "pair:open"
    fun pairing(id: ByteArray) = "pairing:" + hex(id)
    fun verify(peer: ByteArray) = "verify:" + hex(peer)
}
