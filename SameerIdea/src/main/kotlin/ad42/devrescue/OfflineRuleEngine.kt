package ad42.devrescue

// Offline-first rule engine: Python-friendly regexes.
// No AI needed — perfect fallback for demo / judging offline.
data class Explanation(val title: String, val why: String, val fix: String)

object OfflineRuleEngine {

    fun explain(errorText: String): Explanation {
        val t = errorText.trim()
        if (t.isEmpty()) return Explanation(
            "No error selected",
            "Select a stacktrace or error message in the editor or console first.",
            "Highlight the red text, then Alt+Shift+E."
        )

        fun match(vararg keywords: String) = keywords.any { t.contains(it, ignoreCase = true) }

        return when {
            match("NullPointerException", "Cannot read field", "cannot invoke") -> Explanation(
                "NullPointerException — you used something that is null",
                "Some variable/object was null when you called a method or accessed a field on it. " +
                    "Check the line number in the stacktrace — one of the dots (a.b()) has a == null.",
                "1. Add a null-check: if (x != null) …\n2. Use Objects.requireNonNull(x, \"x must not be null\")\n3. In new code prefer Optional or early-return guard clauses."
            )
            match("IndexOutOfBoundsException", "Index 0 out of bounds", "IndexOutOfRange") -> Explanation(
                "Index out of bounds — list/array access past the end",
                "You asked for element N but the list size is <= N (or empty). Classic off-by-one.",
                "1. Check list.size() / len() before access\n2. Loop with `for (i in list.indices)` not `<= size`\n3. Use .getOrNull(i) / safe access in Kotlin."
            )
            match("ClassNotFoundException", "NoClassDefFoundError", "ModuleNotFoundError", "ImportError") -> Explanation(
                "Missing dependency / import",
                "JVM/Python can't find the class or module. Dependency not on classpath / not pip-installed, or wrong package name.",
                "1. Java: check build.gradle dependency + rebuild\n2. Python: pip install <package>\n3. Check for typos in import."
            )
            match("Traceback", "File \"", ".py\"") && match("TypeError", "ValueError", "KeyError", "AttributeError", "IndentationError", "SyntaxError", "NameError") -> explainPython(t)
            match("OutOfMemoryError", "Java heap space") -> Explanation(
                "OutOfMemory — heap exhausted",
                "App tried to allocate more than -Xmx allows. Often a leak (growing list/cache) or too-small heap.",
                "1. Increase -Xmx in run config (e.g. -Xmx2g)\n2. Look for collections that grow forever\n3. Profile with IntelliJ Profiler."
            )
            match("StackOverflowError", "RecursionError") -> Explanation(
                "Infinite recursion",
                "A function calls itself (directly or via cycle) without a base case.",
                "1. Add base case / termination condition\n2. Print first 5 frames of stacktrace — the repeating pair is the cycle."
            )
            match("Port already in use", "Address already in use", "BindException") -> Explanation(
                "Port already in use",
                "Another process (maybe your last run) still holds the port.",
                "1. Kill old process\n2. Change server.port\n3. On Linux: lsof -i :8080 → kill PID."
            )
            match("AccessDenied", "Permission denied", "403", "401", "Unauthorized") -> Explanation(
                "Auth / permission problem",
                "Credentials missing, expired, or lacking scope.",
                "1. Check token/env var is set\n2. Re-login (az login / gh auth login)\n3. Verify role assignment."
            )
            match("cannot find symbol", "unresolved reference", "is not defined", "NameError") -> Explanation(
                "Typo or missing import / declaration",
                "Compiler can't find that name. Typo, missing import, or wrong scope.",
                "1. Alt+Enter in IntelliJ → auto-import\n2. Check spelling + case\n3. Verify file is in source root."
            )
            match("AssertionError", "assert") -> Explanation(
                "Assertion failed",
                "An `assert` or test expectation didn't hold. Your assumption about the code is wrong (or the code is).",
                "1. Read expected vs actual in the message\n2. Add a print/log right before the assert\n3. Run only that single test with debugger."
            )
            else -> Explanation(
                "Unexplained error — here's how to read it",
                "Couldn't match a known pattern. Read stacktrace top-down: first line = error type + message, " +
                    "next lines = call chain. Your bug is usually in the FIRST line that mentions YOUR file (not library code).",
                "1. Copy the first YOUR-file line number → go there\n2. Add a log/print of variables at that line\n3. Paste into DevRescue with an API key for AI explanation."
            )
        }
    }

    private fun explainPython(t: String): Explanation {
        // Last line of a Python traceback is the real error, e.g. "ValueError: invalid literal..."
        val lastLine = t.lines().map { it.trim() }.lastOrNull { it.isNotEmpty() } ?: t
        return when {
            t.contains("IndentationError") || t.contains("unexpected indent") -> Explanation(
                "Python IndentationError",
                "Mixed tabs/spaces or wrong indent level. Python cares about whitespace.",
                "1. In IntelliJ: Code → Reformat Code\n2. Convert tabs to 4 spaces\n3. Check copy-pasted blocks."
            )
            t.contains("KeyError") -> Explanation(
                "Python KeyError — dict key missing ($lastLine)",
                "You accessed dict[key] that doesn't exist.",
                "1. Use dict.get(key) with default\n2. Check `if key in dict:` first\n3. Print dict.keys() before access."
            )
            t.contains("AttributeError") && t.contains("NoneType") -> Explanation(
                "Python NoneType error ($lastLine)",
                "Same as NullPointerException: a function returned None and you used it like an object.",
                "1. Check the function actually returns something (missing `return`?)\n2. Add `if x is None: ...` guard\n3. Add type hints + mypy."
            )
            else -> Explanation(
                "Python error: $lastLine",
                "In Python tracebacks read BOTTOM-UP: last line is the error, lines above are the call chain.",
                "1. Focus on last line + your file's line in the traceback\n2. Print variable types with print(type(x))\n3. Use debugger breakpoint on that line."
            )
        }
    }
}
