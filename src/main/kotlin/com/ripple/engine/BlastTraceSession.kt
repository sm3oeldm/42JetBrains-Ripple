package com.ripple.engine

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.sun.jdi.AbsentInformationException
import com.sun.jdi.Bootstrap
import com.sun.jdi.ClassNotPreparedException
import com.sun.jdi.LocalVariable
import com.sun.jdi.Location
import com.sun.jdi.ReferenceType
import com.sun.jdi.StackFrame
import com.sun.jdi.VMDisconnectedException
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
import com.ripple.blast.NavigationKey
import java.io.File
import java.net.ServerSocket
import java.util.IdentityHashMap

/**
 * Blast-scoped, multi-target JDI recorder.
 *
 * [JdiSession] traces one class over one line range. That is the right shape for
 * "Trace This Method" and the wrong shape for Rewind: a recording is only worth
 * scrubbing if it follows the change OUT through its callers, which means many
 * methods across many classes in a single run, each captured event attributed
 * back to the blast node it belongs to.
 *
 * Three things make that work, and each of them is a place this went wrong
 * before:
 *
 *  1. ONE ClassPrepareRequest PER CLASS, LIVE FOR THE WHOLE RUN. Classes in a
 *     blast radius load at wildly different times — a report renderer may not
 *     load until several seconds in, and may load twice under different
 *     classloaders. Arming once at startup and disabling the request afterwards
 *     loses every late class silently.
 *
 *  2. BREAKPOINTS ON EVERY LOCATION OF EVERY LINE. A loop header compiles to
 *     separate init, condition and increment offsets on the same source line.
 *     Taking `locationsOfLine(n).first()` fires once and misses every iteration,
 *     which is precisely the compounding-discount bug we exist to show.
 *
 *  3. EXACTLY ONE RESUME PER SUSPEND. With `suspend=y` the VM reports its
 *     initial suspension as a VMStartEvent whose EventSet the loop below
 *     resumes. Calling `vm.resume()` as well is a double resume; suspend counts
 *     are per thread, so the extra decrement makes the NEXT suspension silently
 *     fail and the debuggee runs to completion before a single breakpoint is
 *     installed. Measured at a 5-in-6 failure rate. There is no `vm.resume()`
 *     in this file.
 *
 * Blocking. Background thread only — never the EDT. Holds no PSI: targets are
 * plain strings and [NavigationKey]s, so nothing here can go stale while the run
 * is in flight.
 */
class BlastTraceSession(private val config: BlastTraceConfig) {

    private val log = Logger.getInstance(BlastTraceSession::class.java)

    /**
     * One instrumented method, plus the blast nodes that map onto it.
     *
     * Several nodes can collapse into one armed method: [NavigationKey] carries
     * parameter types but a JDI breakpoint only knows a class and a method name,
     * so overloads share their instrumentation. Arming each node separately
     * would install duplicate breakpoints and double-count every event, so they
     * are grouped and all of the group's nodes are credited when it executes.
     */
    private class ArmedMethod(
        val fqcn: String,
        val methodName: String,
        val lines: IntRange,
        val targets: List<TraceTarget>
    ) {
        val primary: TraceTarget = targets.first()
        val visitsByLine: MutableMap<Int, Int> = HashMap()
        val events: MutableList<TraceEvent> = ArrayList()
    }

    /**
     * Record the radius.
     *
     * @return what ran, what did not, and what never even loaded. Never null and
     *         never a lie: a class the debuggee never touched is reported as
     *         such rather than failing the whole recording.
     * @throws ProcessCanceledException if [indicator] is cancelled. Never caught
     *         here — swallowing it makes Cancel look broken and freezes the IDE.
     * @throws IllegalStateException if NOTHING in the radius could be armed,
     *         which almost always means a stale classpath; the message carries
     *         the debuggee's own output, which is what actually diagnoses it.
     */
    fun run(indicator: ProgressIndicator? = null): BlastTraceResult {
        val startedAt = System.currentTimeMillis()
        if (config.targets.isEmpty()) return BlastTraceResult.empty(config)

        val groups: List<ArmedMethod> = buildGroups()
        val groupsByClass: Map<String, List<ArmedMethod>> = groups.groupBy { it.fqcn }

        val orderedEvents = ArrayList<BlastTraceEvent>()
        val executedNodeIds = LinkedHashSet<String>()
        val armedClasses = LinkedHashSet<String>()
        // Identity, not equality: a BreakpointRequest is the exact object we
        // created, and this is the unambiguous attribution path.
        val requestOwners = IdentityHashMap<EventRequest, ArmedMethod>()

        var truncated = false
        var timedOut = false
        var breakpointCount = 0

        val port = freePort()
        val debuggeeLog = debuggeeLogFile()
        val debuggee = launchDebuggee(port, debuggeeLog)
        log.info(
            "Ripple blast trace: ${config.mainClass} on port $port, " +
                "${config.targets.size} targets across ${config.distinctClasses.size} classes"
        )

        var vm: VirtualMachine? = null
        try {
            val machine = attachWithRetry(port)
            vm = machine

            // Arm anything already loaded (rare with suspend=y, but free to check),
            // then keep a live ClassPrepareRequest per class for everything else.
            for (fqcn in config.distinctClasses) {
                val already = arm(machine, fqcn, groupsByClass[fqcn].orEmpty(), requestOwners)
                if (already > 0) {
                    armedClasses += fqcn
                    breakpointCount += already
                }
                // Requested even when already armed: the same name can be loaded
                // again by another classloader, and those locations are different
                // objects that need their own breakpoints.
                val cpr: ClassPrepareRequest = machine.eventRequestManager().createClassPrepareRequest()
                cpr.addClassFilter(fqcn)
                // SUSPEND_ALL, not SUSPEND_EVENT_THREAD: nothing else in the
                // debuggee may make progress while breakpoints are being
                // installed, or a short main() finishes first.
                cpr.setSuspendPolicy(EventRequest.SUSPEND_ALL)
                cpr.enable()
            }

            // NO vm.resume() HERE. See the class comment — the event loop below
            // performs exactly one resume per EventSet it receives, including the
            // VMStartEvent set that releases the initial suspend=y suspension.

            val deadline = System.currentTimeMillis() + config.timeoutMs
            var done = false
            while (!done) {
                indicator?.checkCanceled()
                if (System.currentTimeMillis() > deadline) {
                    timedOut = true
                    truncated = true
                    break
                }
                var set: EventSet? = null
                var disconnected = false
                try {
                    set = machine.eventQueue().remove(POLL_MILLIS)
                } catch (e: VMDisconnectedException) {
                    disconnected = true
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    disconnected = true
                }
                if (disconnected) break
                if (set == null) {
                    // Poll expired with nothing queued. If the debuggee has also
                    // exited, the run is simply over.
                    if (!debuggee.isAlive) done = true
                    continue
                }

                for (ev: Event in set) {
                    when (ev) {
                        is ClassPrepareEvent -> {
                            val name = ev.referenceType().name()
                            val forClass = groupsByClass[name] ?: continue
                            val installed = armType(ev.referenceType(), machine, forClass, requestOwners)
                            if (installed > 0) {
                                armedClasses += name
                                breakpointCount += installed
                            }
                            log.info("Ripple blast: $name prepared, $installed breakpoints")
                        }

                        is BreakpointEvent -> {
                            if (orderedEvents.size >= config.maxEvents) {
                                truncated = true
                                done = true
                                break
                            }
                            val owner = attribute(ev, requestOwners, groupsByClass) ?: continue
                            val line = ev.location().lineNumber()
                            val visit = owner.visitsByLine.getOrDefault(line, 0)
                            owner.visitsByLine[line] = visit + 1
                            val captured = capture(ev, line, visit)
                            owner.events += captured
                            orderedEvents += BlastTraceEvent(
                                globalIndex = orderedEvents.size,
                                key = owner.primary.key,
                                methodQualifiedName = owner.primary.methodQualifiedName,
                                displayName = owner.primary.displayName,
                                event = captured
                            )
                            owner.targets.forEach { executedNodeIds += it.nodeId }
                            // Indeterminate progress: fraction must stay in 0..1,
                            // so only the text is touched here.
                            indicator?.text =
                                "Recording blast radius… ${orderedEvents.size} events, " +
                                    "${executedNodeIds.size} methods"
                        }

                        is VMDeathEvent, is VMDisconnectEvent -> done = true
                    }
                }

                // Exactly one resume for the set we just consumed, on every path
                // out of the loop body above — including the cap `break`.
                try {
                    set.resume()
                } catch (e: VMDisconnectedException) {
                    done = true
                }
            }
        } catch (e: ProcessCanceledException) {
            // NEVER swallowed. This is the platform's cancellation signal; turning
            // it into a "truncated success" makes Cancel look broken and leaves the
            // progress bar lying. Clean up in finally, then rethrow.
            throw e
        } catch (e: VMDisconnectedException) {
            // The debuggee exited from under us. Everything captured so far is real.
            truncated = true
        } catch (e: Exception) {
            if (orderedEvents.isEmpty()) throw e
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

        if (armedClasses.isEmpty() && orderedEvents.isEmpty()) {
            // Not a degradation case: nothing in the radius was even reachable,
            // which is a broken classpath or a main class that died on startup.
            throw IllegalStateException(
                "Blast trace armed nothing: none of ${config.distinctClasses} loaded " +
                    "(main=${config.mainClass}, cp=${config.classpath}). Debuggee output:\n" +
                    debuggeeLogTail(debuggeeLog)
            )
        }

        val neverLoaded = config.distinctClasses.filter { it !in armedClasses }.toSet()
        val byNode = LinkedHashMap<String, NodeTrace>()
        for (group in groups) {
            for (target in group.targets) {
                byNode[target.nodeId] = NodeTrace(
                    key = target.key,
                    displayName = target.displayName,
                    methodQualifiedName = target.methodQualifiedName,
                    events = group.events.toList()
                )
            }
        }

        log.info(
            "Ripple blast: ${orderedEvents.size} events, $breakpointCount breakpoints, " +
                "${executedNodeIds.size}/${config.targets.size} nodes executed, " +
                "neverLoaded=$neverLoaded, truncated=$truncated"
        )

        return BlastTraceResult(
            orderedEvents = orderedEvents.toList(),
            byNode = byNode,
            targetedNodeIds = config.targetedNodeIds,
            executedNodeIds = executedNodeIds.toSet(),
            armedClasses = armedClasses.toSet(),
            neverLoadedClasses = neverLoaded,
            truncated = truncated,
            timedOut = timedOut,
            startedAt = startedAt,
            durationMillis = System.currentTimeMillis() - startedAt
        )
    }

    // ---------------------------------------------------------------- targets

    private fun buildGroups(): List<ArmedMethod> {
        val byMethod = LinkedHashMap<String, MutableList<TraceTarget>>()
        for (t in config.targets) {
            byMethod.getOrPut("${t.fqcn}#${t.methodName}") { ArrayList() }.add(t)
        }
        return byMethod.values.map { targets ->
            ArmedMethod(
                fqcn = targets.first().fqcn,
                methodName = targets.first().methodName,
                // Union of the declared ranges: arming re-checks the owning
                // method of every location, so a widened range is safe.
                lines = targets.minOf { it.lines.first }..targets.maxOf { it.lines.last },
                targets = targets.toList()
            )
        }
    }

    // ----------------------------------------------------------------- arming

    /** Arm every already-loaded version of [fqcn]. Returns breakpoints installed. */
    private fun arm(
        vm: VirtualMachine,
        fqcn: String,
        groups: List<ArmedMethod>,
        owners: IdentityHashMap<EventRequest, ArmedMethod>
    ): Int {
        if (groups.isEmpty()) return 0
        var count = 0
        for (refType in vm.classesByName(fqcn)) {
            count += armType(refType, vm, groups, owners)
        }
        return count
    }

    /**
     * Install breakpoints on ALL locations of every line of every group.
     *
     * Two things here are load-bearing. First, all locations, not the first: a
     * loop header maps to init + condition + increment offsets on one source
     * line, and taking only the first fires once and misses every iteration.
     * Second, the owning-method check: a target's line range may be a superset of
     * its body, and without this a widened range would silently instrument the
     * neighbouring method and attribute its values to the wrong blast node.
     */
    private fun armType(
        refType: ReferenceType,
        vm: VirtualMachine,
        groups: List<ArmedMethod>,
        owners: IdentityHashMap<EventRequest, ArmedMethod>
    ): Int {
        var count = 0
        for (group in groups) {
            for (line in group.lines) {
                val locations: List<Location> = try {
                    refType.locationsOfLine(line)
                } catch (e: AbsentInformationException) {
                    emptyList()
                } catch (e: ClassNotPreparedException) {
                    emptyList()
                }
                for (loc in locations) {
                    if (loc.method().name() != group.methodName) continue
                    val bp: BreakpointRequest = vm.eventRequestManager().createBreakpointRequest(loc)
                    // Per-thread: suspending the world on every line of a hot
                    // loop turns a two second recording into a two minute one.
                    bp.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD)
                    bp.enable()
                    owners[bp] = group
                    count++
                }
            }
        }
        return count
    }

    // ------------------------------------------------------------ attribution

    /**
     * Map a hit back to its blast node.
     *
     * The request identity map is exact and is tried first. The fallback is the
     * documented contract — `declaringType().name()` plus `method().name()` —
     * which also covers a breakpoint installed against a second classloader's
     * copy of the class after the map was built.
     */
    private fun attribute(
        ev: BreakpointEvent,
        owners: IdentityHashMap<EventRequest, ArmedMethod>,
        groupsByClass: Map<String, List<ArmedMethod>>
    ): ArmedMethod? {
        owners[ev.request()]?.let { return it }
        val declaring = ev.location().declaringType().name()
        val method = ev.location().method().name()
        val line = ev.location().lineNumber()
        return groupsByClass[declaring]?.firstOrNull { it.methodName == method && line in it.lines }
    }

    // --------------------------------------------------------------- capture

    /** Snapshot the frame. Reuses [JdiSession.render], so arrays come out BY CONTENTS. */
    private fun capture(be: BreakpointEvent, line: Int, visit: Int): TraceEvent {
        val vars = LinkedHashMap<String, String>()
        var depth = -1
        var thread = "?"
        try {
            val frame: StackFrame = be.thread().frame(0)
            thread = be.thread().name()
            val visible: List<LocalVariable> = try {
                frame.visibleVariables()
            } catch (e: AbsentInformationException) {
                emptyList() // compiled without -g:vars
            }
            for (v in visible) {
                vars[v.name()] = try {
                    JdiSession.render(frame.getValue(v))
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
            // Unreadable frame: record the visit with no vars rather than dropping
            // it, so the timeline still shows that the line ran.
        }
        return TraceEvent(
            lineNumber = line,
            variableSnapshots = vars,
            visitIndex = visit,
            callDepth = depth,
            threadName = thread
        )
    }

    // ------------------------------------------------------------- debuggee

    private fun launchDebuggee(port: Int, logFile: File): Process {
        val cmd = mutableListOf(
            config.javaBin,
            "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=$port",
            "-cp", config.classpath,
            config.mainClass
        ) + config.mainArgs
        log.info("Ripple blast launch: ${cmd.joinToString(" ")}")
        // Never inheritIO: an unread pipe fills and deadlocks the debuggee.
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
        val deadline = System.currentTimeMillis() + ATTACH_TIMEOUT_MS
        var last: Exception? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                return connector.attach(args)
            } catch (e: java.net.ConnectException) {
                last = e
                Thread.sleep(100)
            }
        }
        throw last ?: IllegalStateException("JDI attach failed on port $port")
    }

    private fun debuggeeLogFile(): File {
        val f = File(System.getProperty("java.io.tmpdir"), "ripple-blast-debuggee-last.log")
        try {
            if (f.exists()) f.delete()
        } catch (ignored: Exception) {
        }
        return f
    }

    private fun debuggeeLogTail(f: File): String = try {
        if (!f.isFile) "(no debuggee output captured)"
        else f.readText().takeLast(1500).ifBlank { "(debuggee produced no output)" }
    } catch (e: Exception) {
        "(could not read debuggee log: ${e.message})"
    }

    private fun freePort(): Int {
        ServerSocket(0).use { return it.localPort }
    }

    private companion object {
        /** Short enough that cancellation and the deadline stay responsive. */
        const val POLL_MILLIS = 500L
        const val ATTACH_TIMEOUT_MS = 10_000L
    }
}
