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
    private val sessions = mutableMapOf<String, TraceSession>()

    fun put(session: TraceSession) {
        sessions[session.methodQualifiedName] = session
    }

    fun get(methodQualifiedName: String): TraceSession? = sessions[methodQualifiedName]

    companion object {
        fun getInstance(project: Project): TraceStore = project.service()
    }
}
