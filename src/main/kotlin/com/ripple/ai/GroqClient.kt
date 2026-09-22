package com.ripple.ai

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Minimal Groq chat-completions client.
 *
 * Built on java.net.http from the JDK and the Gson the IDE already bundles, so
 * the plugin adds NO new dependency. That is deliberate: bundling a third-party
 * HTTP or JSON library inside a plugin is how you end up shadowing one of the
 * platform's own and getting a classloader failure that only appears at
 * runtime. We hit exactly that with JNA while spiking voice support.
 *
 * Every call is blocking. Background threads only.
 */
object GroqClient {

    private val log = Logger.getInstance(GroqClient::class.java)

    private const val ENDPOINT = "https://api.groq.com/openai/v1/chat/completions"

    /** Large enough to write good code, fast enough to demo. ~2s in practice. */
    const val DEFAULT_MODEL = "openai/gpt-oss-120b"

    private val http: HttpClient by lazy {
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build()
    }

    sealed interface Result {
        data class Ok(val content: String, val model: String, val tokens: Int) : Result
        /** Any failure. [reason] is shown to the user, so keep it short and plain. */
        data class Failed(val reason: String) : Result
    }

    /**
     * One chat completion.
     *
     * Low temperature on purpose: this generates code that must compile, not
     * prose. Creative sampling here produces plausible-looking tests that do not
     * build, which is worse than no test at all.
     */
    fun complete(
        apiKey: String,
        system: String,
        user: String,
        model: String = DEFAULT_MODEL,
        maxTokens: Int = 1200,
        temperature: Double = 0.2,
        timeout: Duration = Duration.ofSeconds(45)
    ): Result {
        val body = JsonObject().apply {
            addProperty("model", model)
            addProperty("temperature", temperature)
            addProperty("max_tokens", maxTokens)
            add("messages", com.google.gson.JsonArray().apply {
                add(message("system", system))
                add(message("user", user))
            })
        }

        val request = HttpRequest.newBuilder()
            .uri(URI.create(ENDPOINT))
            .timeout(timeout)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()

        return try {
            ProgressManager.checkCanceled()
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            parse(response)
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: java.net.http.HttpTimeoutException) {
            Result.Failed("the model took too long to answer")
        } catch (e: java.net.ConnectException) {
            Result.Failed("could not reach the API — check the network")
        } catch (e: java.net.UnknownHostException) {
            Result.Failed("offline — api.groq.com did not resolve")
        } catch (e: Exception) {
            // Never let the key reach a log or a balloon.
            log.warn("Ripple: Groq call failed (${e.javaClass.simpleName})")
            Result.Failed(e.javaClass.simpleName)
        }
    }

    private fun message(role: String, content: String) = JsonObject().apply {
        addProperty("role", role)
        addProperty("content", content)
    }

    private fun parse(response: HttpResponse<String>): Result {
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            return Result.Failed("the API key was rejected")
        }
        if (response.statusCode() == 429) {
            return Result.Failed("rate limited — try again in a moment")
        }
        if (response.statusCode() !in 200..299) {
            return Result.Failed("API returned ${response.statusCode()}")
        }
        return try {
            val root = JsonParser.parseString(response.body()).asJsonObject
            root.getAsJsonObject("error")?.let {
                return Result.Failed(it.get("message")?.asString ?: "API error")
            }
            val choices = root.getAsJsonArray("choices")
            if (choices == null || choices.isEmpty) return Result.Failed("the model returned nothing")
            val content = choices[0].asJsonObject
                .getAsJsonObject("message")
                ?.get("content")?.asString
                ?: return Result.Failed("the model returned no content")
            val tokens = root.getAsJsonObject("usage")?.get("total_tokens")?.asInt ?: 0
            Result.Ok(content, root.get("model")?.asString ?: "?", tokens)
        } catch (e: Exception) {
            Result.Failed("could not read the API response")
        }
    }

    /**
     * Pull the Java out of a ```java fence.
     *
     * Models wrap code in fences even when told not to, and a stray fence in a
     * generated .java file is a compile error the user has to clean up by hand.
     */
    fun extractCode(raw: String): String {
        val fence = Regex("```(?:java|kotlin)?\\s*\\n([\\s\\S]*?)```")
        val match = fence.find(raw)
        return (match?.groupValues?.get(1) ?: raw).trim()
    }
}
