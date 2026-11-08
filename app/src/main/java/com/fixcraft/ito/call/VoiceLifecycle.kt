package com.fixcraft.ito.call

/** Focus failure during setup is final, even before either device starts. */
internal class VoiceLifecycle {
    private enum class State { New, Starting, Running, Stopped }
    @Volatile private var state = State.New
    val running: Boolean get() = state == State.Running

    @Synchronized fun beginStart() {
        check(state == State.New)
        state = State.Starting
    }

    /** Device startup and the final focus check share one transition. */
    @Synchronized fun startCapture(start: () -> Unit) {
        check(state == State.Starting)
        state = State.Running
        try {
            start()
            check(state == State.Running)
        } catch (error: Throwable) {
            state = State.Stopped
            throw error
        }
    }

    /** Reports each setup/running failure once; close never reports a failure. */
    @Synchronized fun fail(): Boolean {
        if (state != State.Starting && state != State.Running) return false
        state = State.Stopped
        return true
    }

    @Synchronized fun close() { state = State.Stopped }
}
