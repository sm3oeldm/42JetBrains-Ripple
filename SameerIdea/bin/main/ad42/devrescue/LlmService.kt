package ad42.devrescue

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

// No bundled OkHttp (it clashes with the IDE's own copy).
// Uses JDK HttpClient on a pooled thread; falls back to offline rules on any failure.
@Service(Service.Level.APP)
class LlmService {
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .build()

    companion object {
        fun getInstance(): LlmService = service()
    }

    suspend fun explainWithAi(errorText: String, codeContext: String = ""): Explanation =
        withContext(Dispatchers.IO) {
            val s = DevRescueSettings.getInstance()
            val key = s.getApiKey()
            if (key.isBlank()) return@withContext OfflineRuleEngine.explain(errorText)
            try {
                val prompt = buildString {
                    append("You are a senior developer helping a junior. Explain this error simply.\n")
                    append("ERROR:\n$errorText\n")
                    if (codeContext.isNotBlank()) append("\nSURROUNDING CODE:\n$codeContext\n")
                    append("\nReply in exactly this format:\nTITLE: <one line>\nWHY: <2-3 sentences>\nFIX: <numbered steps>\n")
                }
                val json = """{"model":${esc(s.model)},"messages":[{"role":"user","content":${esc(prompt)}}],"temperature":0.3,"max_tokens":600}"""
                val req = HttpRequest.newBuilder()
                    .uri(URI.create(s.baseUrl.trimEnd('/') + "/chat/completions"))
                    .timeout(Duration.ofSeconds(60))
                    .header("Authorization", "Bearer $key")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build()
                val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
                if (resp.statusCode() !in 200..299) return@withContext OfflineRuleEngine.explain(errorText)
                val content = extractContent(resp.body()) ?: return@withContext OfflineRuleEngine.explain(errorText)
                return@withContext parseAi(content)
            } catch (_: Exception) {
                OfflineRuleEngine.explain(errorText)
            }
        }

    // Backgroundable wrapper so the IDE shows progress + Cancel instead of a frozen UI.
    fun explainInBackground(project: Project, errorText: String, code: String, done: (Explanation, Boolean, String) -> Unit) {
        val s = DevRescueSettings.getInstance()
        object : Task.Backgroundable(project, "DevRescue: explaining error", true) {
            var result: Explanation = OfflineRuleEngine.explain(errorText)
            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Contacting ${s.model}…"
                // Blocking bridge: run the suspend fn to completion on this worker thread.
                result = kotlinx.coroutines.runBlocking { explainWithAi(errorText.take(4000), code) }
            }
            override fun onSuccess() {
                val key = s.getApiKey()
                done(result, key.isNotBlank(), s.model)
            }
            override fun onThrowable(e: Throwable) {
                done(OfflineRuleEngine.explain(errorText), false, s.model)
            }
        }.queue()
    }

    private fun parseAi(content: String): Explanation {
        fun section(name: String): String? =
            Regex("$name\\s*:\\s*(.+?)(?=\\n[A-Z]+\\s*:|\\z)", RegexOption.DOT_MATCHES_ALL)
                .find(content)?.groupValues?.get(1)?.trim()
        val t = section("TITLE")
        val w = section("WHY")
        val f = section("FIX")
        if (t.isNullOrBlank() || w.isNullOrBlank()) {
            return Explanation("AI explanation", content.take(1500), "See explanation above.")
        }
        return Explanation(t, w, f ?: "")
    }

    private fun extractContent(json: String): String? {
        val i = json.indexOf("\"content\"")
        if (i < 0) return null
        // Handle: "content":null (reasoning-only models) and "content":"..."
        val after = json.substring(i + 9).trimStart().removePrefix(":").trimStart()
        if (after.startsWith("null")) return null
        val start = json.indexOf('"', i + 9)
        if (start < 0) return null
        val sb = StringBuilder()
        var j = start + 1
        while (j < json.length) {
            val c = json[j]
            if (c == '\\' && j + 1 < json.length) {
                when (json[j + 1]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    else -> sb.append(json[j + 1])
                }
                j += 2
            } else if (c == '"') break
            else { sb.append(c); j++ }
        }
        // Some reasoning models put text in reasoning_content instead
        if (sb.isBlank()) {
            val r = json.indexOf("\"reasoning_content\"")
            if (r >= 0) return "(reasoning-only reply — model returned no final text; offline rules apply)"
            return null
        }
        return sb.toString()
    }

    private fun esc(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""
}
