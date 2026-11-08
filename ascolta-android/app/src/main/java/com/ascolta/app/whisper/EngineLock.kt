package com.ascolta.app.whisper

import kotlinx.coroutines.sync.Mutex

/** Only one speech engine runs at a time: they use most of the CPU and a lot of memory. */
object EngineLock {
    val mutex = Mutex()
}
