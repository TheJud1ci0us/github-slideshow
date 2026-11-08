package com.ascolta.app.whisper

import kotlinx.coroutines.sync.Mutex

/** Only one speech engine runs at a time: they use most of the CPU and a lot of memory. */
object EngineLock {
    val mutex = Mutex()
    /** Jobs waiting for [mutex]; a job that is only waiting for the listener steps aside for them. */
    val waiting = java.util.concurrent.atomic.AtomicInteger()
}
