# Ripple

**In-editor execution-flow and state time machine for IntelliJ IDEA.**

Built for the 42 Abu Dhabi × JetBrains Hackathon, 22–23 September 2026 — *Help the Developer*.

---

## The problem

Debugging means breakpoints, stepping, and losing spatial context between your code and its state. You read a loop, then look away at a variables panel, then look back, and you hold the mapping in your head.

Ripple brings runtime state **into the editor**, automatically — no breakpoints, no stepping, no manual debugger interaction.

## What it does

### 1. Inline State Trails

Put the caret in a method and press <kbd>Ctrl</kbd>+<kbd>Alt</kbd>+<kbd>T</kbd>.

Ripple launches your program under the Java Debug Interface, breaks on every code location of every line in that method, snapshots every visible local variable, and resumes automatically. When the run finishes, the recorded values are painted as inline chips at the end of each line.

You see what the code *actually did*, in place, without touching a debugger.

For a loop, each line shows its last recorded pass plus a visit count (`×5`), so a value that diverges on one iteration is visible without stepping.

### 2. Edge-Case Hypothesizer

A `LocalInspectionTool` that flags two high-signal, deterministic boundary risks:

- **Off-by-one** — a `for` bound comparing with `<=` against a `.length` / `.size()` expression.
- **Unguarded recursion** — a method that calls itself with no guarded base case.

<kbd>Alt</kbd>+<kbd>Enter</kbd> on either offers **Generate micro-test for this edge case**, which writes a JUnit 5 skeleton into a scratch file with boundary literals pre-filled per parameter type.

No LLM. Entirely deterministic, so it fires identically every time.

### 3. Call-Graph Gutter Lens

An icon beside every method name opens a popup with **Called by** and **Calls**, each navigable on double-click. The reference search runs in a background read action, so a large project never freezes the UI.

---

## IntelliJ Platform SDK components used

| Component | Where | Why |
|---|---|---|
| `com.sun.jdi` (Java Debug Interface) | `engine/JdiSession.kt` | Drives the traced JVM directly rather than going through `XDebugSession`, which is built for interactive human-in-the-loop debugging and would add a large integration surface for no gain. |
| `InlayModel` + `EditorCustomElementRenderer` | `inlay/` | Low-level inlay API rather than the declarative `InlayHintsProvider`, because trails are on-demand and session-scoped, not always-on. |
| `LocalInspectionTool`, `ProblemsHolder`, `LocalQuickFix` | `inspection/` | Idiomatic inspection + quick-fix pipeline, so the warning and the bulb behave exactly like any built-in inspection. |
| `JavaElementVisitor` / PSI | `inspection/EdgeCaseVisitor.kt` | Real AST analysis — not regex over text. |
| `LineMarkerProvider` | `gutter/` | Gutter icons, anchored to leaf `PsiIdentifier` elements as the platform requires. |
| `ReferencesSearch`, `PsiTreeUtil` | `gutter/CallGraphPopupPanel.kt` | Caller/callee resolution through the platform index. |
| `ReadAction.nonBlocking`, `Task.Backgroundable` | throughout | Every expensive operation runs off the EDT with a cancellable progress indicator. |
| `ScratchRootType` | `inspection/GenerateMicroTestFix.kt` | Generated tests land in a scratch buffer, never in the user's source tree. |

**Fully offline.** No network calls, no LLM, no external services on any code path.

---

## Build and run

Requires JDK 21.

```bash
./gradlew buildPlugin     # produces build/distributions/ripple-0.1.0.zip
./gradlew test            # headless inspection tests
./gradlew runIde          # sandbox IDE with the plugin loaded
```

Install the built zip into any IDEA 2024.3+ via **Settings → Plugins → ⚙ → Install Plugin from Disk**.

## Trying it

`sample-trace-demo/Demo.java` contains a deliberately broken array reversal that produces a **silently wrong result** — no exception, so only the value trail reveals it.

```bash
cd sample-trace-demo
javac -g --release 21 -d out Demo.java
```

`-g` matters: without local-variable debug info there is nothing for the tracer to read.
`--release 21` matters: the tracer launches the program on the IDE's Java 21 runtime.

Then open the file in the sandbox IDE, put the caret in `reverse`, and press <kbd>Ctrl</kbd>+<kbd>Alt</kbd>+<kbd>T</kbd>.

## Known limits

- **Java only.** The tracer resolves Java PSI and JDI locations; other JVM languages are out of scope.
- **Entry point is `main()` in the same class.** Full run-configuration selection is not implemented.
- **Capped by design** — 5,000 events or 10 seconds, whichever comes first. A runaway program degrades to a partial trail rather than a frozen IDE.
- The trail records a **focused session**, not a production trace.

## Team

42 Abu Dhabi × JetBrains Hackathon.
