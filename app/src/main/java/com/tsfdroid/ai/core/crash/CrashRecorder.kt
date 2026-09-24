package com.tsfdroid.ai.core.crash

/** Records a crash. Implementations must never throw. */
interface CrashRecorder {
    fun record(thread: Thread, throwable: Throwable)
}
