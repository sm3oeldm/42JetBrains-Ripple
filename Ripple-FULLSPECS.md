# Ripple — Build Specification
**In-Editor Execution-Flow & State Time Machine (IntelliJ Plugin)**

Target event: **42 Abu Dhabi × JetBrains Hackathon**, Sept 22–23, 2026 ("Help the Developer")
Audience for this document: an autonomous coding agent (e.g. Claude Code) that will scaffold, implement, and iterate on the plugin with minimal human hand-holding.

---

## 0. Read this first — how to use this document

This is not a wishlist. It is scoped for a ~20–24 hour build window by 1–3 people. It is organized as:

1. **Reality check** — which parts of the original pitch are directly buildable in the timebox, and which need a scoped-down implementation to be honest and demoable.
2. **Locked architecture** — the actual APIs and data flow to implement. Don't re-derive this; implement it.
3. **File-by-file build plan** — in priority order. Each phase ends in something runnable.
4. **Hour-by-hour schedule** — a checkpoint plan so the agent (and team) always knows if it's behind.
5. **Demo script** — write the demo scenario code *first*, then build features to make that scenario shine.
6. **Fallbacks** — what to cut if a phase overruns.

**Golden rule for the agent: always keep the plugin in a runnable, demoable state.** Commit after every working milestone. Never leave the tree in a state where `./gradlew runIde` fails for more than one phase at a time.

---

## 1. Reality check on the original pitch

The original concept has three features. Here's an honest feasibility read for a 24h build:

| Feature | As pitched | Feasibility in 24h | Verdict |
|---|---|---|---|
| Inline State Trails | "Automatic" ghosted values updating live as code runs, like a lens | True live step-by-step tracking during arbitrary execution is what a full debugger does — reimplementing that generically in 24h is not realistic | **Scope down**: run-once "record & replay" trace. User clicks "Trace this method", the plugin runs the program to completion under instrumentation, records every visit to every line inside the method with variable snapshots, then paints the *last recorded pass* (or a scrubbable pass) as inlays. This is still "no manual stepping," still visually stunning, and is buildable. |
| Mini Call-Graph Gutter Lens | Interactive collapsible micro-diagram of callers/callees | PSI-based caller/callee resolution is very buildable. A full "diagram" renderer is not, in 24h, worth building from scratch. | **Scope down**: gutter icon → popup with a simple, static two-column tree (callers above, callees below) using IntelliJ's built-in `Tree`/`JBPopup` components — not a custom diagram canvas. Clicking a node navigates to it. This looks clean and demoes fine without needing a graphics layout engine. |
| Edge-Case Auto-Hypothesizer | AST-based boundary risk detection + auto-generated micro-tests into a scratch buffer | Fully buildable with PSI visitors + a `LocalInspectionTool`/`IntentionAction`. No LLM needed for the hackathon version — use deterministic heuristics. | **Keep as pitched**, deterministic version. LLM-backed smarter version is a stretch goal only. |

**Scope decision:** Build in this priority order, stop whenever time runs out:
1. **P0 — Inline State Trails** (the single most "wow" feature, and the true differentiator vs. existing tools). This is also the riskiest technically, so it goes first while there's time to recover if it's harder than expected.
2. **P1 — Edge-Case Auto-Hypothesizer** (lowest technical risk, fast to build, good demo variety).
3. **P2 — Call-Graph Gutter Lens** (nice-to-have, cut first if behind schedule; a plugin with P0+P1 alone is still a complete, coherent, demoable product).

Do **not** attempt to build all three to the original spec's depth. A working P0 + P1 beats three half-broken features.

---

## 2. Judging alignment (keep visible while building)

- **JetBrains judges** care about idiomatic use of the Platform SDK: PSI, not regex-on-text; `LocalInspectionTool`/`IntentionAction` for the hypothesizer; `LineMarkerProvider` for gutter icons; `InlayModel`/`EditorCustomElementRenderer` for inline hints; proper use of `plugin.xml` extension points, not reflection hacks. Every "how it looks under the hood" moment in the demo is worth narrating.
- **42 Abu Dhabi judges** care about algorithmic rigor and that the tool visibly helps you reason about a real bug. The demo must center on a genuinely tricky bug (off-by-one, mutation during iteration, or unguarded recursion) — not a trivial one.
- **Both** care about the 3-minute demo being tight, visual, and needing zero manual debugger interaction on stage.

---

## 3. Locked architecture

### 3.1 Tech stack

- **Language:** Kotlin (JVM), targeting Java as the traced/analyzed language (do not attempt multi-language support — Java only for the hackathon).
- **Build system:** [IntelliJ Platform Gradle Plugin 2.x](https://plugins.jetbrains.com/docs/intellij/tools-gradle-intellij-plugin.html) via the official [`intellij-platform-plugin-template`](https://github.com/JetBrains/intellij-platform-plugin-template) as scaffold.
- **Target IDE:** IntelliJ IDEA Community, a recent 2024.x/2025.x build available locally. Pin `platformVersion` in `gradle.properties` to whatever IDE is installed on the build machine — check first with `idea64.exe /?` or `Help | About` rather than guessing.
- **JDK:** 21 for the plugin itself. The *traced* program can be any Java version the JDI connector supports — for the demo, keep the sample project on Java 17/21 for simplicity.
- **No external UI frameworks.** Use Swing/IntelliJ UI DSL (`JBPopup`, `Tree`, `JBList`) — this is what the SDK expects and it's faster to get looking "native" than any custom rendering.
- **No web services / no LLM calls in the core path.** Everything must work fully offline (many hackathon venues have unreliable wifi, and judges may ask you to unplug from the network). If an LLM stretch goal is attempted, it must be optional and the plugin must degrade gracefully without it.

### 3.2 Module map

```
ripple/
├── build.gradle.kts
├── gradle.properties
├── settings.gradle.kts
└── src/main/
    ├── kotlin/com/ripple/
    │   ├── actions/
    │   │   └── TraceMethodAction.kt        # right-click / gutter action: "Trace This Method"
    │   ├── engine/
    │   │   ├── TraceEngine.kt              # orchestrates a JDI trace run
    │   │   ├── JdiSession.kt               # low-level JDI attach/breakpoint/event-loop wrapper
    │   │   ├── TraceEvent.kt               # data class: line, variable snapshots, iteration/call index
    │   │   └── TraceSession.kt             # in-memory result: List<TraceEvent> keyed by PsiMethod
    │   ├── inlay/
    │   │   ├── TraceInlayProvider.kt       # InlayHintsProvider (or manual InlayModel calls)
    │   │   └── TraceValueRenderer.kt       # EditorCustomElementRenderer for the ghosted value chips
    │   ├── gutter/
    │   │   ├── CallGraphLineMarkerProvider.kt
    │   │   └── CallGraphPopupPanel.kt      # Tree-based caller/callee popup
    │   ├── inspection/
    │   │   ├── EdgeCaseInspection.kt       # LocalInspectionTool
    │   │   ├── EdgeCaseVisitor.kt          # PsiElementVisitor with the heuristics
    │   │   └── GenerateMicroTestFix.kt     # LocalQuickFix / IntentionAction -> scratch file
    │   └── util/
    │       └── PsiMethodUtil.kt            # shared PSI helpers (line ranges, containing method, etc.)
    └── resources/META-INF/
        └── plugin.xml
```

### 3.3 Core data model

```kotlin
// engine/TraceEvent.kt
data class TraceEvent(
    val lineNumber: Int,              // 1-based source line, matches PSI/document line
    val variableSnapshots: Map<String, String>, // var name -> toString()'d value at this line
    val visitIndex: Int,              // 0,1,2... which pass through this line (loop iteration)
    val callDepth: Int,               // recursion / nested-call depth at time of hit
    val threadName: String
)

// engine/TraceSession.kt
data class TraceSession(
    val methodQualifiedName: String,
    val events: List<TraceEvent>,
    val startedAt: Long,
    val truncated: Boolean            // true if we hit the safety cap (see 3.5)
) {
    fun eventsForLine(line: Int): List<TraceEvent> = events.filter { it.lineNumber == line }
}
```

Keep an in-memory `Project`-level service (`@Service(Service.Level.PROJECT)`) holding the *last* `TraceSession` per traced method qualified name. No persistence needed for the hackathon — memory is fine, sessions are cheap and short-lived.

### 3.4 Feature 1 (P0): Inline State Trails via JDI

**Why JDI directly, not IntelliJ's `XDebugger` API:** IntelliJ's own debugger UI (`XDebugSession`, `XDebugProcess`) is built for *interactive* human-in-the-loop debugging tied to Run Configurations, and hooking a plugin into it to drive it silently, end-to-end, in an automated loop is a much bigger integration surface than needed. Instead, drive the **Java Debug Interface (`com.sun.jdi`)** directly — this is the same protocol IntelliJ's debugger is built on, but using it standalone is well-documented, self-contained, and fully controllable without any UI ceremony. This is the load-bearing technical decision of this project — implement it exactly this way.

**Flow:**

1. **Trigger.** User places the caret in a method (or selects it via the gutter icon added in 3.6) and invokes the `Trace This Method` action (`Alt+Enter` intention, or a toolbar/gutter icon — wire both to the same handler).
2. **Resolve target.** From the PSI (`PsiMethod` via `PsiTreeUtil.getParentOfType`), get:
   - Fully qualified containing class name.
   - The method's source line range (`PsiDocumentManager` → `Document.getLineNumber(element.textOffset)` for start/end).
   - The project's run configuration / main class to actually execute (reuse the project's existing "Run" configuration if one exists and is a Java app; otherwise prompt the user to pick one via a simple `ComboBox` dialog, or — simplest for the hackathon demo — require the sample project to have a single obvious `main` class and just launch that).
3. **Launch debuggee.** Build a `ProcessBuilder`/`GeneralCommandLine` that launches the target JVM with:
   ```
   java -agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=<free_port> -cp <classpath> <MainClass>
   ```
   Pick a free local port via `ServerSocket(0)` then close it immediately before use (standard "find free port" trick).
4. **Attach.** Use `com.sun.jdi.Bootstrap.virtualMachineManager().attachingConnectors()`, find the `socket` connector, connect with `hostname`/`port` args, get the `VirtualMachine`.
5. **Set breakpoints for every line in the method body.** Resolve the target class via `vm.classesByName(fqcn)` (may need to enable class-prepare events and wait, since the class may not be loaded yet at attach time — use `ClassPrepareRequest` filtered to the FQCN, then set line breakpoints once the class-prepare event fires). For each line number in the method's PSI range, do:
   ```kotlin
   val location = refType.locationsOfLine(lineNumber).firstOrNull() ?: continue
   val req = vm.eventRequestManager().createBreakpointRequest(location)
   req.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD) // not SUSPEND_ALL — keep other threads running
   req.enable()
   ```
6. **Event loop.** On a background thread, pull `vm.eventQueue().remove()` in a loop:
   - On `BreakpointEvent`: get `event.thread().frame(0)`, call `frame.visibleVariables()` and `frame.getValues(variables)`, `.toString()` every value defensively (wrap in try/catch — some `.toString()` calls can throw or be expensive; cap string length, e.g. 200 chars, and catch `ObjectCollectedException`/`InvalidStackFrameException`). Record a `TraceEvent`. Track a per-line visit counter for `visitIndex` and a call-depth counter (increment on method-entry breakpoint if you add one, or approximate via `frame.thread().frameCount()`).
   - **Always resume automatically**: `event.thread().resume()` (or `vm.resume()` if `SUSPEND_ALL` was used — avoid that; per-thread suspend keeps things faster and simpler). This is what makes it feel "automatic" — no user interaction, ever.
   - On `VMDeathEvent`/`VMDisconnectEvent`: stop the loop, finalize the `TraceSession`.
7. **Safety caps (must implement, this is easy to get wrong live on stage):**
   - Max total events per session (e.g. 5,000) — if exceeded, disable further breakpoint requests, mark `truncated = true`, keep whatever was captured.
   - Wall-clock timeout on the whole trace (e.g. 10 seconds) — if the program hangs or infinite-loops, kill the process (`Process.destroyForcibly()`) and show whatever was captured, not a frozen IDE. Run the event loop and the process wait on separate threads/coroutines from the EDT — **never block the UI thread**.
   - Show a small progress indicator (`ProgressManager.getInstance().run(...)` as a background task) while tracing runs.
8. **Render.** Once the session completes, hand the `TraceSession` to the inlay layer (3.5).

**Do not** attempt full multi-threaded trace correlation, watch expressions, or object-graph diffing for the hackathon. Primitives and `.toString()` of objects is enough — arrays/collections' `.toString()` is usually informative enough for a demo (e.g. `[3, 1, 4, 1, 5]`).

### 3.5 Rendering: `InlayModel` inline value chips

Use the low-level `InlayModel` API directly (not the higher-level `InlayHintsProvider` declarative framework, which is more geared at persistent/always-on hints and adds indirection you don't need for an on-demand, session-scoped feature).

```kotlin
// inlay/TraceValueRenderer.kt
class TraceValueRenderer(private val text: String) : EditorCustomElementRenderer {
    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        val editor = inlay.editor
        return editor.contentComponent.getFontMetrics(font(editor)).stringWidth(text) + 12
    }
    override fun calcHeightInPixels(inlay: Inlay<*>): Int = 0 // inline, uses line height
    override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: TextAttributes) {
        val editor = inlay.editor
        g.color = JBColor(0xEDF3FF.toInt(), 0x2B2D30) // light/dark aware background chip
        g.fillRoundRect(targetRegion.x, targetRegion.y, targetRegion.width, targetRegion.height, 6, 6)
        g.color = JBColor.namedColor("Label.infoForeground", JBColor.GRAY)
        g.font = font(editor)
        g.drawString(text, targetRegion.x + 6, targetRegion.y + targetRegion.height - 4)
    }
    private fun font(editor: Editor) = editor.colorsScheme.getFont(EditorFontType.PLAIN).deriveFont(Font.PLAIN, editor.colorsScheme.editorFontSize2D - 1)
}
```

Rendering pass, driven right after a `TraceSession` finishes:

```kotlin
fun renderTrace(editor: Editor, session: TraceSession) {
    val inlayModel = editor.inlayModel
    // Clear any previous chips this plugin added (track your own Inlay handles in a list, dispose() them first)
    for (line in session.events.map { it.lineNumber }.distinct()) {
        val eventsOnLine = session.eventsForLine(line)
        val last = eventsOnLine.last()
        val label = if (eventsOnLine.size > 1)
            "${summarize(last.variableSnapshots)}  (×${eventsOnLine.size})"
        else summarize(last.variableSnapshots)
        val offset = editor.document.getLineEndOffset(line - 1) // end-of-line inline hint, like a tracepoint
        inlayModel.addInlineElement(offset, true, TraceValueRenderer(label))
    }
}
private fun summarize(vars: Map<String,String>) = vars.entries.joinToString("  ") { (k,v) -> "$k=$v" }
```

**"Time Machine" scrubbing (if time allows, otherwise skip):** add a tiny floating `JBPopup` or a status-bar widget with a slider from `0` to `max(visitIndex)`, and re-render inlays filtered to that iteration on change. This is the P0 stretch — the static "last value per line" version above is already a complete, demoable feature without it. Build the static version first, add scrubbing only once it works.

**Cleanup:** register a document/editor listener (or simply clear inlays on the next `Trace This Method` invocation, and on caret leaving the file) so stale chips don't linger and confuse the demo.

### 3.6 Feature 2 (P1): Edge-Case Auto-Hypothesizer

Two pieces: an **inspection** (so risky code gets a visible warning + the `Alt+Enter` bulb) and a **quick fix / intention** (generates the micro-test).

```kotlin
// inspection/EdgeCaseInspection.kt
class EdgeCaseInspection : LocalInspectionTool() {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        EdgeCaseVisitor(holder)
}
```

```kotlin
// inspection/EdgeCaseVisitor.kt
class EdgeCaseVisitor(private val holder: ProblemsHolder) : JavaElementVisitor() {

    override fun visitForStatement(statement: PsiForStatement) {
        super.visitForStatement(statement)
        val condition = statement.condition as? PsiBinaryExpression ?: return
        // Heuristic: loop bound compares to a `.length`/`.size()` call using `<=` (classic off-by-one risk)
        val opText = condition.operationSign.text
        val rhsText = condition.rOperand?.text.orEmpty()
        if (opText == "<=" && (rhsText.contains(".length") || rhsText.contains(".size("))) {
            holder.registerProblem(
                condition,
                "Possible off-by-one: loop bound uses '<=' against a length/size expression",
                GenerateMicroTestFix(statement)
            )
        }
    }

    override fun visitMethod(method: PsiMethod) {
        super.visitMethod(method)
        // Heuristic: unguarded recursion — method calls itself with no visible base-case `if`/`return` before the call
        val body = method.body ?: return
        val selfCalls = PsiTreeUtil.findChildrenOfType(body, PsiMethodCallExpression::class.java)
            .filter { it.resolveMethod() == method }
        if (selfCalls.isNotEmpty()) {
            val hasEarlyReturn = PsiTreeUtil.findChildrenOfType(body, PsiIfStatement::class.java)
                .any { PsiTreeUtil.findChildOfType(it.thenBranch, PsiReturnStatement::class.java) != null }
            if (!hasEarlyReturn) {
                holder.registerProblem(
                    method.nameIdentifier ?: method,
                    "Recursive method with no obvious guarded base case — risk of unbounded recursion / StackOverflowError",
                    GenerateMicroTestFix(method)
                )
            }
        }
    }

    override fun visitReferenceExpression(expression: PsiReferenceExpression) {
        super.visitReferenceExpression(expression)
        // Heuristic: chained calls after a method known to be able to return null (very small, explicit allowlist —
        // do NOT attempt full nullability inference in 24h) without a preceding null check in the same block.
        // Keep this heuristic simple and narrow; false negatives are fine, false positives kill the demo.
    }
}
```

Keep the heuristic set to **2–3 well-chosen patterns** you can reliably trigger on cue during the demo, rather than a broad sweep that risks false positives on stage. Off-by-one loop bound and unguarded recursion are both high-signal and easy to reproduce live.

```kotlin
// inspection/GenerateMicroTestFix.kt
class GenerateMicroTestFix(private val target: PsiElement) : LocalQuickFix {
    override fun getFamilyName() = "Generate micro-test for this edge case"
    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val method = PsiTreeUtil.getParentOfType(target, PsiMethod::class.java, false) ?: return
        val testSource = buildTestSkeleton(method) // pure string templating, no LLM call
        val scratchFile = ScratchRootType.getInstance().createScratchFile(
            project, "${method.name}EdgeCaseTest.java", JavaLanguage.INSTANCE, testSource
        )
        scratchFile?.let { FileEditorManager.getInstance(project).openFile(it, true) }
    }
}
```

`buildTestSkeleton` is plain Kotlin string templating: read the method's parameter types/names via PSI, emit a JUnit 5 test class with 2–3 boundary-value test method stubs (`// TODO: assert expected behavior at boundary`) pre-filled with a call to the method using boundary-ish literal values (`0`, `-1`, empty collection, `null` where the type allows it). This does not need to be semantically perfect — it needs to visibly save the developer typing, on stage.

### 3.7 Feature 3 (P2, cut first if behind): Call-Graph Gutter Lens

```kotlin
// gutter/CallGraphLineMarkerProvider.kt
class CallGraphLineMarkerProvider : LineMarkerProvider {
    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        if (element !is PsiIdentifier) return null
        val method = element.parent as? PsiMethod ?: return null
        return LineMarkerInfo(
            element, element.textRange, AllIcons.Actions.Diff, // swap for a small custom icon if time allows
            { "View callers / callees" }, // tooltip
            { _, _ -> showCallGraphPopup(method) },
            GutterIconRenderer.Alignment.LEFT,
            { "Ripple call graph" }
        )
    }
}
```

Data gathering:
- **Callers:** `ReferencesSearch.search(method).findAll()` → map each usage to its containing `PsiMethod` (`PsiTreeUtil.getParentOfType`).
- **Callees:** `PsiTreeUtil.findChildrenOfType(method.body, PsiMethodCallExpression::class.java)` → `.resolveMethod()` on each, dedupe.

Render with a `Tree`/`SimpleTree` inside a `JBPopupFactory.getInstance().createComponentPopupBuilder(...)` — root node = the clicked method, one child branch "Called by" and one "Calls", each populated with clickable nodes that navigate on double-click (`NavigatablePsiElement.navigate(true)`). This is a standard, well-supported IntelliJ UI pattern — do not hand-roll a canvas/diagram renderer under time pressure.

---

## 4. `plugin.xml` skeleton

```xml
<idea-plugin>
    <id>com.ripple.plugin</id>
    <name>Ripple</name>
    <vendor>42 Abu Dhabi x JetBrains Hackathon Team</vendor>
    <description>In-editor execution-flow and state time machine.</description>
    <depends>com.intellij.modules.platform</depends>
    <depends>com.intellij.modules.java</depends>

    <extensions defaultExtensionNs="com.intellij">
        <localInspection language="JAVA"
                         displayName="Ripple edge-case hypothesizer"
                         groupName="Ripple"
                         enabledByDefault="true"
                         level="WARNING"
                         implementationClass="com.ripple.inspection.EdgeCaseInspection"/>
        <codeInsight.lineMarkerProvider language="JAVA"
                         implementationClass="com.ripple.gutter.CallGraphLineMarkerProvider"/>
    </extensions>

    <actions>
        <action id="Ripple.TraceMethod"
                class="com.ripple.actions.TraceMethodAction"
                text="Trace This Method"
                description="Run this method under Ripple and show inline state trails">
            <add-to-group group-id="EditorPopupMenu" anchor="first"/>
            <keyboard-shortcut keymap="$default" first-keystroke="control alt T"/>
        </action>
    </actions>
</idea-plugin>
```

---

## 5. File-by-file build plan (priority order)

**Phase 0 — Scaffold (target: <45 min)**
1. Generate project from `intellij-platform-plugin-template` (or `New Project | IDE Plugin` in IntelliJ Ultimate/with the Plugin DevKit plugin installed).
2. Set `platformVersion` in `gradle.properties` to the locally installed IDE build.
3. Confirm `./gradlew runIde` launches a sandbox IDE with the empty plugin loaded. **Do not proceed until this works.**
4. Create a tiny sample Java project (see §6) to open inside the sandbox IDE for all subsequent testing — build this now so every later phase has something real to test against immediately.

**Phase 1 — P0 Inline State Trails (target: the bulk of hour 1–8)**
1. `engine/TraceEvent.kt`, `engine/TraceSession.kt` — data classes, no logic, gets everything else compiling early.
2. `engine/JdiSession.kt` — attach/launch/breakpoint/event-loop, tested standalone first against the sample project's `main` method with a hardcoded line range, printing captured values to `println`/log before touching any UI. **Prove JDI capture works before writing a single line of inlay rendering code.**
3. `actions/TraceMethodAction.kt` — wires caret-position → `PsiMethod` resolution → `JdiSession` invocation, running on a background task (`Task.Backgroundable`) with a progress indicator.
4. `inlay/TraceValueRenderer.kt` + rendering pass — once `JdiSession` reliably returns a `TraceSession`, wire it to `InlayModel`. Test on a small loop function.
5. Checkpoint: can trigger the action on a real buggy loop and see values appear inline with zero manual stepping. **This is the phase that must not slip — if it's taking too long, cut recursion/call-depth tracking and multi-visit indexing; ship "value at each line, last visit only" and move on.**

**Phase 2 — P1 Edge-Case Auto-Hypothesizer (target: 2–3 hours)**
1. `inspection/EdgeCaseVisitor.kt` with the off-by-one heuristic only, get the warning squiggle showing in the sandbox IDE.
2. Add the unguarded-recursion heuristic.
3. `inspection/GenerateMicroTestFix.kt` — string-template test generation into a scratch file, wired as the quick fix.
4. Checkpoint: `Alt+Enter` on a flagged loop produces a usable test skeleton in a scratch buffer.

**Phase 3 — P2 Call-Graph Gutter Lens (target: remaining time, cut entirely if <2h left)**
1. `gutter/CallGraphLineMarkerProvider.kt` — get the gutter icon showing.
2. `gutter/CallGraphPopupPanel.kt` — caller/callee `Tree` in a popup, navigation on click.
3. Checkpoint: clicking the gutter icon on a method with 2+ callers/callees shows both directions and navigation works.

**Phase 4 — Polish & demo hardening (protect at least the last 1.5–2 hours for this, non-negotiable)**
1. Run through the exact demo script (§7) start to finish, twice, on a clean sandbox launch.
2. Fix anything that requires the presenter to explain away a glitch.
3. Add a plugin icon (`pluginIcon.svg` in resources, 40×40 and 80×80 per SDK convention) — small visual polish that reads as "finished" to judges.
4. Make sure `./gradlew buildPlugin` succeeds and produces a working `.zip` — some judging processes install the built plugin rather than watching a live `runIde` session.

---

## 6. Sample demo project (build this in Phase 0, not as an afterthought)

Create a tiny standalone Java project (separate from the plugin project, opened inside the sandbox IDE) with **one deliberately broken method** that is:
- Small enough to read on a projector in 5 seconds.
- Has a real, non-contrived bug that an off-by-one or mutation-during-iteration issue causes.
- Produces a wrong result silently (no exception) — so the value trail is what reveals the bug, not a stack trace.

Good candidate: an array-processing function with an off-by-one that corrupts an aggregate on the last iteration, e.g. a running-max/sum calculation that reads one index past where it should, or a manual array reversal that overwrites a value before it's been read. Write this method **first**, confirm by hand exactly which line and which iteration the state goes wrong on, and design the demo narration around that specific, verifiable moment. Do not leave the choice of "which bug to demo" until the polish phase.

---

## 7. Demo script (3 minutes, rehearse against a clock)

1. **(20s) The problem.** One line: "Debugging means breakpoints, stepping, and losing spatial context between code and state. Ripple brings runtime state into the editor, automatically."
2. **(30s) Show the bug.** Open the sample project's broken method. Point at the suspicious loop bound. "This looks right. It isn't."
3. **(45s) Inline State Trails.** Trigger `Trace This Method` (keyboard shortcut, not menu-diving). Values populate inline within a couple seconds. Point directly at the line/iteration where the value diverges from what it should be. This is the money shot — let it breathe on screen for a beat before talking over it.
4. **(45s) Edge-Case Hypothesizer.** `Alt+Enter` on the same loop (or a second prepared snippet) → show the warning → generate the micro-test → show the scratch buffer with a usable test stub. "Same bug, caught statically, with a test ready to prove it."
5. **(30s, only if P2 shipped) Call-Graph Lens.** Click the gutter icon on a method with a couple of callers, show the popup, navigate to a caller.
6. **(10s) Close.** One line back to the platform-fit pitch: "Built entirely on the IntelliJ Platform SDK — PSI, JDI, and InlayModel, no external services, works fully offline."

Keep a **second, pre-recorded screen capture of a successful run** as a backup in case live demo has a bad moment (JDI attach flakiness on an unfamiliar demo machine is the single most likely failure point — see §8).

---

## 8. Known risks & fallbacks

| Risk | Likelihood | Fallback |
|---|---|---|
| JDI attach fails/hangs on the demo machine (firewall, port conflicts, JDK mismatch) | Medium | Test on the *actual* demo machine well before presenting, not just a dev laptop. Keep the backup screen recording. Add a hard timeout (§3.4 step 7) so a failure degrades to "no trace captured, try again" rather than a frozen IDE. |
| PSI line-number-to-JDI-location mapping is off by one somewhere (very common bug class in this kind of tool) | High | Budget explicit test time for this in Phase 1 — verify against a method with 4–5 distinct, easily eyeballed lines before trusting it on the real demo method. |
| Inspection heuristics produce false positives on the demo project's other code, distracting during the walkthrough | Medium | Scope heuristics narrowly (§3.6) and/or disable the inspection on files other than the demo file for the presentation, re-enabling only when about to trigger it. |
| Running out of time on P2 | High (by design — it's lowest priority) | Cut it. A two-feature plugin that works flawlessly beats three that don't. Say so confidently in the pitch if asked — judges respect honest scoping. |
| `runIde` sandbox is slow to boot between test iterations, burning build time | Medium | Keep the sandbox instance running across a work session instead of restarting it for every change where possible; use IntelliJ's dynamic plugin reload where the SDK supports it for the specific extension points you're using (inspections and line markers typically reload cleanly; the JDI action code can be re-invoked without a full IDE restart in most cases — verify early). |

---

## 9. Stretch goals (only after P0+P1, and ideally P2, are demo-solid)

- Time-machine scrubber slider for inline trails (§3.5).
- Highlight the *specific line* where a tracked variable's value diverges from its value on the previous loop iteration (a "diff" cue — colored inlay background) — very high demo impact for relatively little extra code once the base trail works.
- LLM-backed (optional, gracefully-degrading) smarter test generation for the hypothesizer, clearly marked as opt-in / requires an API key, never on the critical demo path.
- Persisting the last N trace sessions per project (`PersistentStateComponent`) so re-opening a file doesn't lose the last trace.

Do not start on any of these until the core demo is rock solid and rehearsed.

---

## 10. Definition of done for the hackathon submission

- [ ] `./gradlew buildPlugin` succeeds with no errors.
- [ ] Plugin installs into a clean IDE instance via `Install Plugin from Disk`.
- [ ] Inline State Trails works on the specific demo method, twice in a row, on the actual demo machine.
- [ ] Edge-Case Hypothesizer triggers and generates a real test stub on the specific demo method.
- [ ] Demo has been rehearsed against a clock at least twice, under 3 minutes.
- [ ] A short README exists explaining the SDK components used (judges will read this even if they don't ask live) — `InlayModel`, `com.sun.jdi`, `LocalInspectionTool`, `LineMarkerProvider`, `PsiElementVisitor`.
- [ ] Backup screen recording exists in case of live-demo failure.
