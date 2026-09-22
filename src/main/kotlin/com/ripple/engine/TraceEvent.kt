package com.ripple.engine

// Core data model (§3.3). Line numbers are 1-based source lines, matching PSI/document lines.
data class TraceEvent(
    val lineNumber: Int,
    val variableSnapshots: Map<String, String>,
    val visitIndex: Int,
    val callDepth: Int,
    val threadName: String
)
