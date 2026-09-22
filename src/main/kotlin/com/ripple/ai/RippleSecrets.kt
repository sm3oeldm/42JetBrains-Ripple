package com.ripple.ai

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.io.File
import java.util.Properties

/**
 * Where the API key comes from — and, more importantly, where it does NOT.
 *
 * The key is NEVER in source. This repository is submitted to judges; a key
 * committed here is a key leaked to everyone who clones it, and git history
 * keeps it even after a later deletion.
 *
 * Resolution order, first hit wins:
 *   1. IntelliJ's PasswordSafe — the idiomatic place, OS-encrypted, and what a
 *      shipped plugin would use. Survives across projects.
 *   2. The RIPPLE_GROQ_KEY / GROQ_API_KEY environment variables — convenient
 *      for CI and for running the sandbox.
 *   3. .ripple-local.properties at the project root — gitignored, for local dev.
 *
 * If none are present the caller must degrade to the deterministic generator
 * rather than failing: no network is a supported state, not an error.
 */
object RippleSecrets {

    private val log = Logger.getInstance(RippleSecrets::class.java)

    private const val LOCAL_FILE = ".ripple-local.properties"
    private const val LOCAL_KEY = "groq.api.key"

    private val credentialAttributes: CredentialAttributes
        get() = CredentialAttributes(generateServiceName("Ripple", "groq.api.key"))

    /** The key, or null if the user has not provided one anywhere. */
    fun groqKey(project: Project?): String? {
        passwordSafe()?.let { return it }

        sequenceOf("RIPPLE_GROQ_KEY", "GROQ_API_KEY")
            .mapNotNull { System.getenv(it) }
            .firstOrNull { it.isNotBlank() }
            ?.let { return it.trim() }

        localFile(project)?.let { return it }

        log.info("Ripple: no Groq key found; AI generation will fall back to the template generator")
        return null
    }

    /** Persist the key in the OS credential store. */
    fun storeGroqKey(key: String) {
        try {
            PasswordSafe.instance.setPassword(credentialAttributes, key.trim().ifEmpty { null })
        } catch (e: Exception) {
            log.warn("Ripple: could not write the key to PasswordSafe", e)
        }
    }

    fun hasKey(project: Project?): Boolean = !groqKey(project).isNullOrBlank()

    private fun passwordSafe(): String? = try {
        PasswordSafe.instance.getPassword(credentialAttributes)?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        null
    }

    private fun localFile(project: Project?): String? {
        val base = project?.basePath ?: return null
        // Also look one level up: the demo project is opened as its own project,
        // while the key file sits beside the plugin it belongs to.
        val candidates = listOf(File(base, LOCAL_FILE), File(File(base).parentFile, LOCAL_FILE))
        for (f in candidates) {
            if (!f.isFile) continue
            try {
                val props = Properties()
                f.inputStream().use { props.load(it) }
                props.getProperty(LOCAL_KEY)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
            } catch (e: Exception) {
                log.warn("Ripple: could not read $LOCAL_FILE", e)
            }
        }
        return null
    }
}
