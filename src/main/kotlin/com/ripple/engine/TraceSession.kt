package com.ripple.engine

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

data class TraceSession(
    val methodQualifiedName: String,
    val events: List<TraceEvent>,
    val startedAt: Long,
    val truncated: Boolean
) {
    fun eventsForLine(line: Int): List<TraceEvent> = events.filter { it.lineNumber == line }
}

// In-memory store: last TraceSession per traced method. No persistence needed for hackathon.
@Service(Service.Level.PROJECT)
class TraceStore {
    // Concurrent and BOUNDED. Written from the EDT in onSuccess and read from the
    // scrubber, so a plain mutableMapOf was a data race. It also never evicted:
    // up to 5000 TraceEvents, each carrying a variable map, per traced method,
    // retained for the life of the project.
    private val sessions = java.util.concurrent.ConcurrentHashMap<String, TraceSession>()

    fun put(session: TraceSession) {
        if (sessions.size >= MAX_SESSIONS && !sessions.containsKey(session.methodQualifiedName)) {
            // Keep the newest. Order is not tracked, so drop an arbitrary entry
            // rather than growing without limit.
            sessions.keys.firstOrNull()?.let { sessions.remove(it) }
        }
        sessions[session.methodQualifiedName] = session
    }

    fun get(methodQualifiedName: String): TraceSession? = sessions[methodQualifiedName]

    companion object {
        /** Enough for any demo or debugging session; stops unbounded retention. */
        private const val MAX_SESSIONS = 16

        fun getInstance(project: Project): TraceStore = project.service()
    }
}
