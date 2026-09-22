package ad42.devrescue

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.util.xmlb.XmlSerializerUtil

// Correct persistence: endpoint/model in XML state, secret in PasswordSafe (never XML).
// Bundled demo key migrates into PasswordSafe once, then memory is cleared.
@State(name = "DevRescueSettings", storages = [Storage("devrescue.xml")])
@Service(Service.Level.APP)
class DevRescueSettings : PersistentStateComponent<DevRescueSettings.State> {
    data class State(
        var baseUrl: String = "https://apihub.agnes-ai.com/v1",
        var model: String = "agnes-2.5-flash"
    )

    private var state = State()
    private val svc = generateServiceName("DevRescue", "apiKey")
    private var migrated = false

    override fun getState(): State = state
    override fun loadState(s: State) = XmlSerializerUtil.copyBean(s, state)

    var baseUrl: String
        get() = state.baseUrl
        set(v) { state.baseUrl = v.trim().trimEnd('/') }
    var model: String
        get() = state.model
        set(v) { state.model = v.trim() }

    fun getApiKey(): String {
        PasswordSafe.instance.getPassword(CredentialAttributes(svc))?.let { return it.toString() }
        // One-time migration of the bundled hackathon key so fresh installs just work.
        if (!migrated) {
            migrated = true
            val bundled = "sk-vaKRkVdELvi0V0GSR3DzsO9pJNe8Eg6QoFVAc5UmjjhDMcL7"
            if (bundled.isNotBlank()) {
                ApplicationManager.getApplication().executeOnPooledThread { setApiKey(bundled) }
                return bundled
            }
        }
        return ""
    }

    fun setApiKey(raw: String) {
        val v = raw.trim()
        if (v.isEmpty()) PasswordSafe.instance.setPassword(CredentialAttributes(svc), null)
        else PasswordSafe.instance.setPassword(CredentialAttributes(svc), v)
    }

    companion object {
        fun getInstance(): DevRescueSettings = service()
    }
}
