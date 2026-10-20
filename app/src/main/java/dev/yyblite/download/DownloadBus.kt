package dev.yyblite.download

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

enum class Phase { QUEUED, RUNNING, DONE, FAILED }

data class DownloadState(
    val pkg: String,
    val phase: Phase,
    val bytes: Long = 0,
    val total: Long = -1,
    val error: String? = null,
    val version: String? = null,
    val abi: String? = null
)

object DownloadBus {
    private val states = ConcurrentHashMap<String, DownloadState>()
    private val listeners = CopyOnWriteArrayList<(DownloadState) -> Unit>()

    fun snapshot(): Map<String, DownloadState> = HashMap(states)

    fun subscribe(listener: (DownloadState) -> Unit): AutoCloseable {
        listeners.add(listener)
        return AutoCloseable { listeners.remove(listener) }
    }

    fun publish(state: DownloadState) {
        states[state.pkg] = state
        listeners.forEach { it(state) }
    }

    fun clear(pkg: String) {
        states.remove(pkg)
    }
}
