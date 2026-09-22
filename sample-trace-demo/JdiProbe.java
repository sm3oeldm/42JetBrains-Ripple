import com.sun.jdi.Bootstrap;
import com.sun.jdi.ObjectCollectedException;
import com.sun.jdi.VMDisconnectedException;
import com.sun.jdi.Value;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.ClassPrepareEvent;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.event.VMDeathEvent;
import com.sun.jdi.event.VMDisconnectEvent;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.ClassPrepareRequest;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.ArrayReference;
import com.sun.jdi.BooleanValue;
import com.sun.jdi.ByteValue;
import com.sun.jdi.CharValue;
import com.sun.jdi.DoubleValue;
import com.sun.jdi.FloatValue;
import com.sun.jdi.IntegerValue;
import com.sun.jdi.LocalVariable;
import com.sun.jdi.Location;
import com.sun.jdi.LongValue;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.ShortValue;
import com.sun.jdi.StackFrame;
import com.sun.jdi.StringReference;
import java.io.File;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// Standalone JDI proof (§5 Phase 1, step 2): JDK only, no IDE, no Gradle.
// Launches sample Demo under JDWP, breaks on reverse() lines 21-22,
// prints every captured variable snapshot. Run from sample-trace-demo/:
//   javac -d out Demo.java JdiProbe.java && java -cp out JdiProbe
public class JdiProbe {
    static final int MAX_EVENTS = 5000;
    static final long TIMEOUT_MS = 12000;

    // Demo.reverse() body range. Lines with no executable code (the signature,
    // the closing brace, comments) simply yield no location and are skipped.
    static final int REVERSE_FIRST_LINE = 22;
    static final int REVERSE_LAST_LINE = 26;

    // Usage: java -cp <cp> JdiProbe [classpath mainClass targetClass firstLine lastLine]
    // Defaults reproduce the original Demo.reverse() check.
    public static void main(String[] args) throws Exception {
        String cp        = args.length > 0 ? args[0] : "out";
        String mainClass = args.length > 1 ? args[1] : "Demo";
        String target    = args.length > 2 ? args[2] : "Demo";
        int firstLine    = args.length > 3 ? Integer.parseInt(args[3]) : REVERSE_FIRST_LINE;
        int lastLine     = args.length > 4 ? Integer.parseInt(args[4]) : REVERSE_LAST_LINE;

        int port;
        try (ServerSocket s = new ServerSocket(0)) { port = s.getLocalPort(); }

        String javaBin = System.getProperty("java.home") + File.separator + "bin"
                + File.separator + "java";
        Process proc = new ProcessBuilder(
                javaBin,
                "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=" + port,
                "-cp", cp, mainClass)
                .inheritIO()
                .start();

        VirtualMachine vm = attach(port);
        System.out.println("[probe] attached to " + mainClass + " on port " + port);

        ClassPrepareRequest cpr = vm.eventRequestManager().createClassPrepareRequest();
        cpr.addClassFilter(target);
        // SUSPEND_ALL, not SUSPEND_EVENT_THREAD: nothing else may make progress
        // while we are installing breakpoints, or a short main() finishes first.
        cpr.setSuspendPolicy(EventRequest.SUSPEND_ALL);
        cpr.enable();

        // DO NOT call vm.resume() here.
        //
        // With suspend=y the VM starts suspended and reports that as a
        // VMStartEvent whose EventSet we resume in the loop below. Resuming
        // here as well is a DOUBLE resume: JDI suspend counts are per thread
        // and resume() decrements them, so the extra decrement drives the count
        // negative and the *next* suspension - our ClassPrepareEvent - silently
        // does not suspend anything. The program then runs to completion in
        // microseconds, the VM exits, and every locationsOfLine() call fails
        // with VMDisconnectedException.
        //
        // Symptom when this is wrong: "0 events captured", intermittently.
        // Exactly one resume per suspend. The loop does all of them.

        List<String> log = new ArrayList<>();
        Map<Integer, Integer> visits = new HashMap<>();
        boolean done = false;
        boolean truncated = false;
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        int total = 0;

        while (!done) {
            if (System.currentTimeMillis() > deadline) {
                System.out.println("[probe] TIMEOUT — killing debuggee");
                truncated = true;
                break;
            }
            EventSet set;
            try {
                set = vm.eventQueue().remove(1000); // timed poll so timeout is honored
            } catch (VMDisconnectedException e) { break; }
            if (set == null) {
                if (!proc.isAlive()) { done = true; }
                continue;
            }
            for (Event ev : set) {
                if (ev instanceof ClassPrepareEvent) {
                    ClassPrepareEvent cpe = (ClassPrepareEvent) ev;
                    ReferenceType rt = cpe.referenceType();
                    System.out.println("[probe] class prepared: " + rt.name()
                            + " | suspendPolicy=" + set.suspendPolicy()
                            + " | eventThreadSuspended=" + cpe.thread().isSuspended()
                            + " | vmAlive=" + proc.isAlive()
                            + " | allLineLocations=" + safeCount(rt));
                    // Scan the whole reverse() method range rather than two
                    // hardcoded lines. Hardcoded constants silently rot the
                    // moment a comment shifts the file (they did: the method
                    // moved from 21-22 to 23-24), and a probe that reports
                    // "0 events" looks like a broken tracer rather than a
                    // stale constant. The plugin derives this range from PSI;
                    // the probe now mirrors that by scanning a superset.
                    for (int line = firstLine; line <= lastLine; line++) {
                        List<Location> locs;
                        try { locs = rt.locationsOfLine(line); }
                        catch (Exception e) {
                            System.out.println("[probe] locationsOfLine(" + line + ") threw "
                                    + e.getClass().getName() + ": " + e.getMessage());
                            locs = List.of();
                        }
                        if (locs.isEmpty()) {
                            System.out.println("[probe] no code location for line " + line);
                            continue;
                        }
                        // ALL locations: loop headers map to init + condition/increment
                        // offsets, and firstOrNull() would only ever fire the init once.
                        for (Location loc : locs) {
                            BreakpointRequest bp = vm.eventRequestManager()
                                    .createBreakpointRequest(loc);
                            bp.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
                            bp.enable();
                        }
                        System.out.println("[probe] " + locs.size()
                                + " breakpoint(s) set at " + target + ":" + line);
                    }
                } else if (ev instanceof BreakpointEvent) {
                    BreakpointEvent be = (BreakpointEvent) ev;
                    if (total >= MAX_EVENTS) {
                        truncated = true;
                        done = true;
                        break;
                    }
                    int line = be.location().lineNumber();
                    int visit = visits.getOrDefault(line, 0);
                    visits.put(line, visit + 1);
                    Map<String, String> vars = new HashMap<>();
                    try {
                        StackFrame frame = be.thread().frame(0);
                        List<LocalVariable> visible;
                        try { visible = frame.visibleVariables(); }
                        catch (Exception e) { visible = List.of(); } // absent debug info
                        for (LocalVariable v : visible) {
                            try { vars.put(v.name(), render(frame.getValue(v))); }
                            catch (Exception e) { vars.put(v.name(), "?"); }
                        }
                        int depth;
                        try { depth = be.thread().frameCount(); }
                        catch (Exception e) { depth = -1; }
                        log.add("line=" + line + " visit=" + visit + " depth=" + depth
                                + " thread=" + be.thread().name() + " vars=" + vars);
                    } catch (Exception e) {
                        log.add("line=" + line + " visit=" + visit + " <frame unreadable: " + e + ">");
                    }
                    total++;
                } else if (ev instanceof VMDeathEvent || ev instanceof VMDisconnectEvent) {
                    done = true;
                }
            }
            try { set.resume(); } catch (VMDisconnectedException e) { done = true; }
        }

        try { vm.dispose(); } catch (Exception ignored) {}
        proc.destroyForcibly();
        System.out.println("[probe] ==== TRACE (" + log.size() + " events, truncated=" + truncated + ") ====");
        for (String l : log) System.out.println("[trace] " + l);
        if (log.isEmpty()) { System.out.println("[probe] FAIL: no events captured"); System.exit(1); }
    }

    static int safeCount(ReferenceType rt) {
        try { return rt.allLineLocations().size(); }
        catch (Exception e) { return -1; }
    }

    static VirtualMachine attach(int port) throws Exception {
        var vmm = Bootstrap.virtualMachineManager();
        var connector = vmm.attachingConnectors().stream()
                .filter(c -> c.name().equals("com.sun.jdi.SocketAttach"))
                .findFirst().orElseThrow();
        Map<String, Connector.Argument> a = connector.defaultArguments();
        a.get("hostname").setValue("127.0.0.1");
        a.get("port").setValue(String.valueOf(port));
        // The debuggee's JDWP listener may take a moment to come up after spawn.
        long deadline = System.currentTimeMillis() + 10000;
        Exception last = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                return connector.attach(a);
            } catch (java.net.ConnectException e) {
                last = e;
                Thread.sleep(100);
            }
        }
        throw last;
    }

    // Primitives render natively; arrays render CONTENTS (ArrayReference.toString()
    // is just "instance of int[]" — useless for the demo); everything capped.
    static String render(Value v) {
        if (v == null) return "null";
        try {
            if (v instanceof IntegerValue) return String.valueOf(((IntegerValue) v).value());
            if (v instanceof LongValue) return String.valueOf(((LongValue) v).value());
            if (v instanceof BooleanValue) return String.valueOf(((BooleanValue) v).value());
            if (v instanceof CharValue) return "'" + ((CharValue) v).value() + "'";
            if (v instanceof DoubleValue) return String.valueOf(((DoubleValue) v).value());
            if (v instanceof FloatValue) return String.valueOf(((FloatValue) v).value());
            if (v instanceof ShortValue) return String.valueOf(((ShortValue) v).value());
            if (v instanceof ByteValue) return String.valueOf(((ByteValue) v).value());
            if (v instanceof StringReference) return "\"" + ((StringReference) v).value() + "\"";
            if (v instanceof ArrayReference) {
                ArrayReference arr = (ArrayReference) v;
                int len = arr.length();
                StringBuilder sb = new StringBuilder("[");
                int show = Math.min(len, 12);
                for (int i = 0; i < show; i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(render(arr.getValue(i)));
                }
                if (len > show) sb.append(", ...(").append(len).append(")]");
                else sb.append("]");
                return sb.toString();
            }
            String s = v.toString();
            return s.length() > 200 ? s.substring(0, 200) + "..." : s;
        } catch (ObjectCollectedException e) {
            return "<collected>";
        } catch (Exception e) {
            return "<?>";
        }
    }
}
