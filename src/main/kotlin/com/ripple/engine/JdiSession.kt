package com.ripple.engine

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.sun.jdi.ArrayReference
import com.sun.jdi.Bootstrap
import com.sun.jdi.BooleanValue
import com.sun.jdi.ByteValue
import com.sun.jdi.CharValue
import com.sun.jdi.DoubleValue
import com.sun.jdi.FloatValue
import com.sun.jdi.IntegerValue
import com.sun.jdi.LocalVariable
import com.sun.jdi.LongValue
import com.sun.jdi.ObjectCollectedException
import com.sun.jdi.ShortValue
import com.sun.jdi.StackFrame
import com.sun.jdi.StringReference
import com.sun.jdi.Value
import com.sun.jdi.VirtualMachine
import com.sun.jdi.connect.Connector
import com.sun.jdi.event.BreakpointEvent
import com.sun.jdi.event.ClassPrepareEvent
import com.sun.jdi.event.Event
import com.sun.jdi.event.EventSet
import com.sun.jdi.event.VMDeathEvent
import com.sun.jdi.event.VMDisconnectEvent
import com.sun.jdi.request.BreakpointRequest
import com.sun.jdi.request.ClassPrepareRequest
import com.sun.jdi.request.EventRequest
import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.net.ServerSocket

// Low-level JDI driver (§3.4): launches the target JVM suspended under JDWP,
// attaches over a local socket, breaks on every code location of every line in
// range, snapshots visible variables, auto-resumes. Blocking call — run off-EDT.
data class JdiTraceConfig(
    val javaBin: String,
    val classpath: String,
    val mainClass: String,
    val mainArgs: List<String> = emptyList(),
    val targetClass: String,
    val methodQualifiedName: String,
    val lines: IntRange,
    val maxEvents: Int = 5000,
    val timeoutMs: Long = 10_000
)

class JdiSession(private val config: JdiTraceConfig) {
    private val log = Logger.getInstance(JdiSession::class.java)

    fun run(indicator: ProgressIndicator? = null): TraceSession {
        val startedAt = System.currentTimeMillis()
        val port = freePort()
        val debuggeeLog = debuggeeLogFile()
        val debuggee = launchDebuggee(port, debuggeeLog)
        log.info("Ripple trace: ${config.mainClass} on port $port, log=$debuggeeLog")
        var vm: VirtualMachine? = null
        val events = mutableListOf<TraceEvent>()
        val visits = mutableMapOf<Int, Int>()
        var truncated = false
        var sawClass = false
        try {
            vm = attachWithRetry(port)
            // Target class is never loaded yet (debuggee starts suspended), but check anyway.
            if (vm.classesByName(config.targetClass).isNotEmpty()) {
                sawClass = true
                setBreakpoints(vm, config.targetClass)
            } else {
                val cpr: ClassPrepareRequest = vm.eventRequestManager().createClassPrepareRequest()
                cpr.addClassFilter(config.targetClass)
                cpr.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD)
                cpr.enable()
            }
            vm.resume()

            val deadline = System.currentTimeMillis() + config.timeoutMs
            var done = false
            while (!done) {
                indicator?.checkCanceled()
                if (System.currentTimeMillis() > deadline) {
                    truncated = true
                    break
                }
                val set: EventSet? = try {
                    vm.eventQueue().remove(500)
                } catch (e: Exception) {
                    break // VM disconnected
                }
                if (set == null) {
                    if (!debuggee.isAlive) done = true
                    continue
                }
                for (ev: Event in set) {
                    when (ev) {
                        is ClassPrepareEvent -> {
                            if (ev.referenceType().name() == config.targetClass) {
                                sawClass = true
                                val n = setBreakpoints(vm, config.targetClass)
                                log.info("Ripple: class prepared, $n breakpoints set")
                            }
                        }
                        is BreakpointEvent -> {
                            if (events.size >= config.maxEvents) {
                                truncated = true
                                done = true
                                break
                            }
                            val line = ev.location().lineNumber()
                            if (line !in config.lines) continue
                            val visit = visits.getOrDefault(line, 0)
                            visits[line] = visit + 1
                            events += capture(ev, line, visit)
                            // Indeterminate progress is set via isIndeterminate by the
                            // caller; fraction must stay within 0..1 so never assign here.
                            indicator?.text = "Tracing ${config.methodQualifiedName}… ${events.size} events"
                        }
                        is VMDeathEvent, is VMDisconnectEvent -> done = true
                    }
                }
                try {
                    set.resume()
                } catch (e: Exception) {
                    done = true
                }
            }
        } catch (e: ProcessCanceledException) {
            // NEVER swallow this. It is the platform's cancellation signal; turning a
            // user cancel into a "truncated success" makes the Cancel button look broken
            // and leaves the progress bar lying. Clean up in finally, then rethrow.
            throw e
        } catch (e: Exception) {
            // Genuine JDI failure: keep whatever partial trace we captured.
            if (events.isEmpty()) throw e
            truncated = true
        } finally {
            try {
                vm?.dispose()
            } catch (ignored: Exception) {
            }
            try {
                if (debuggee.isAlive) debuggee.destroyForcibly()
            } catch (ignored: Exception) {
            }
        }
        if (!sawClass && events.isEmpty()) {
            // The #1 failure mode: the debuggee died before loading the target class
            // (bad classpath, main not found). Surface its stderr instead of "0 snapshots".
            throw IllegalStateException(
                "Debuggee never loaded ${config.targetClass} " +
                    "(cp=${config.classpath}). Debuggee output:\n${debuggeeLogTail(debuggeeLog)}"
            )
        }
        log.info("Ripple: ${events.size} events, truncated=$truncated")
        return TraceSession(
            methodQualifiedName = config.methodQualifiedName,
            events = events.toList(),
            startedAt = startedAt,
            truncated = truncated
        )
    }

    private fun setBreakpoints(vm: VirtualMachine, fqcn: String): Int {
        val refTypes = vm.classesByName(fqcn)
        if (refTypes.isEmpty()) return 0
        val refType = refTypes[0]
        var count = 0
        for (line in config.lines) {
            val locs = try {
                refType.locationsOfLine(line)
            } catch (e: Exception) {
                emptyList()
            }
            // ALL locations: a loop header maps to init + condition/increment offsets;
            // firstOrNull() would only fire the init once and miss every iteration.
            for (loc in locs) {
                val bp: BreakpointRequest = vm.eventRequestManager().createBreakpointRequest(loc)
                bp.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD)
                bp.enable()
                count++
            }
        }
        return count
    }

    private fun capture(be: BreakpointEvent, line: Int, visit: Int): TraceEvent {
        val vars = mutableMapOf<String, String>()
        var depth = -1
        var thread = "?"
        try {
            val frame: StackFrame = be.thread().frame(0)
            thread = be.thread().name()
            val visible: List<LocalVariable> = try {
                frame.visibleVariables()
            } catch (e: Exception) {
                emptyList() // absent debug info (class compiled without -g:vars)
            }
            for (v in visible) {
                vars[v.name()] = try {
                    render(frame.getValue(v))
                } catch (e: Exception) {
                    "?"
                }
            }
            depth = try {
                be.thread().frameCount()
            } catch (e: Exception) {
                -1
            }
        } catch (e: Exception) {
            // unreadable frame — record the visit with no vars rather than dropping it
        }
        return TraceEvent(
            lineNumber = line,
            variableSnapshots = vars,
            visitIndex = visit,
            callDepth = depth,
            threadName = thread
        )
    }

    private fun debuggeeLogFile(): File {
        // Stable path (overwritten each run): never inheritIO — an unread pipe deadlocks.
        val f = File(System.getProperty("java.io.tmpdir"), "ripple-debuggee-last.log")
        try {
            if (f.exists()) f.delete()
        } catch (ignored: Exception) {
        }
        return f
    }

    private fun debuggeeLogTail(f: File): String {
        return try {
            if (!f.isFile) "(no debuggee output captured)"
            else f.readText().takeLast(1500).ifBlank { "(debuggee produced no output)" }
        } catch (e: Exception) {
            "(could not read debuggee log: ${e.message})"
        }
    }

    private fun launchDebuggee(port: Int, logFile: File): Process {
        val cmd = mutableListOf(
            config.javaBin,
            "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=$port",
            "-cp", config.classpath,
            config.mainClass
        ) + config.mainArgs
        log.info("Ripple launch: ${cmd.joinToString(" ")}")
        return ProcessBuilder(cmd)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
            .redirectErrorStream(true)
            .start()
    }

    private fun attachWithRetry(port: Int): VirtualMachine {
        val vmm = Bootstrap.virtualMachineManager()
        val connector = vmm.attachingConnectors()
            .firstOrNull { it.name() == "com.sun.jdi.SocketAttach" }
            ?: throw IllegalStateException("No JDI SocketAttach connector")
        val args: Map<String, Connector.Argument> = connector.defaultArguments()
        args["hostname"]?.setValue("127.0.0.1")
        args["port"]?.setValue(port.toString())
        val deadline = System.currentTimeMillis() + 10_000
        var last: Exception? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                return connector.attach(args)
            } catch (e: java.net.ConnectException) {
                last = e
                Thread.sleep(100)
            }
        }
        throw last ?: IllegalStateException("JDI attach failed")
    }

    private fun freePort(): Int {
        ServerSocket(0).use { return it.localPort }
    }

    companion object {
        // Primitives natively; arrays by CONTENTS (ArrayReference.toString() is useless);
        // everything defensive + capped — some toString()s throw or are expensive.
        fun render(v: Value?): String {
            if (v == null) return "null"
            return try {
                when (v) {
                    is IntegerValue -> v.value().toString()
                    is LongValue -> v.value().toString()
                    is BooleanValue -> v.value().toString()
                    is CharValue -> "'${v.value()}'"
                    is DoubleValue -> v.value().toString()
                    is FloatValue -> v.value().toString()
                    is ShortValue -> v.value().toString()
                    is ByteValue -> v.value().toString()
                    is StringReference -> "\"${v.value()}\""
                    is ArrayReference -> {
                        val len = v.length()
                        val show = minOf(len, 12)
                        val items = (0 until show).joinToString(", ") { render(v.getValue(it)) }
                        if (len > show) "[$items, ...($len)]" else "[$items]"
                    }
                    else -> {
                        val s = v.toString()
                        if (s.length > 200) s.substring(0, 200) + "..." else s
                    }
                }
            } catch (e: ObjectCollectedException) {
                "<collected>"
            } catch (e: Exception) {
                "<?>"
            }
        }
    }
}
