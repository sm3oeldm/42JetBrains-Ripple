package ad42.devrescue

import com.intellij.ide.util.PropertiesComponent

// Tiny RPG state: XP -> level, streak, kills. Persisted so demo keeps score.
object GameState {
    private const val K_XP = "devrescue.xp"
    private const val K_FIXED = "devrescue.fixed"
    private const val K_SCANNED = "devrescue.scanned"
    private const val K_STREAK = "devrescue.streak"

    var xp: Int = 0
    var bugsFixed: Int = 0
    var filesScanned: Int = 0
    var streak: Int = 0

    init { load() }

    fun load() {
        try {
            val p = PropertiesComponent.getInstance()
            xp = p.getInt(K_XP, 0)
            bugsFixed = p.getInt(K_FIXED, 0)
            filesScanned = p.getInt(K_SCANNED, 0)
            streak = p.getInt(K_STREAK, 0)
        } catch (_: Exception) { }
    }

    fun save() {
        try {
            val p = PropertiesComponent.getInstance()
            p.setValue(K_XP, xp, 0)
            p.setValue(K_FIXED, bugsFixed, 0)
            p.setValue(K_SCANNED, filesScanned, 0)
            p.setValue(K_STREAK, streak, 0)
        } catch (_: Exception) { }
    }

    val level: Int get() = xp / 300 + 1
    val xpInLevel: Int get() = xp % 300
    val title: String get() = when (level) {
        1 -> "Script Noob"
        2 -> "Bug Hunter"
        3 -> "Code Knight"
        4 -> "Stack Slayer"
        5 -> "Null Pointer Ninja"
        6 -> "Refactor Wizard"
        else -> "10x Legend"
    }

    fun addXp(n: Int) { xp += n; save() }
    fun bugFixed() { bugsFixed++; streak++; addXp(50) }
    fun fileScanned(score: Int) { filesScanned++; addXp(if (score >= 85) 30 else 15); save() }
}
