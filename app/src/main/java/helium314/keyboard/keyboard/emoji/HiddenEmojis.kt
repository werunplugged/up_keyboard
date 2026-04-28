// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.emoji

import android.content.Context
import androidx.core.content.edit
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs

/**
 * Tracks emojis the user has chosen to hide from the keyboard, popups, and emoji search.
 * Mirrors the [SupportedEmojis] pattern: an in-memory set loaded once at app start, queried
 * during emoji parsing, and persisted as a delimited string in SharedPreferences.
 *
 * Hiding the base emoji of an entry hides all its skin-tone / popup variants too — see
 * [isHidden] which checks against the base form (the part before any space in a line).
 */
object HiddenEmojis {
    private const val SEPARATOR = "" // SOH control char, never appears in emojis

    private val hiddenEmojis = hashSetOf<String>()

    fun load(context: Context) {
        hiddenEmojis.clear()
        val raw = context.prefs().getString(Settings.PREF_HIDDEN_EMOJIS, Defaults.PREF_HIDDEN_EMOJIS)
            ?: return
        if (raw.isEmpty()) return
        hiddenEmojis.addAll(raw.split(SEPARATOR).filter { it.isNotEmpty() })
    }

    /** Returns true if [emoji] (or the base form of a variant string) is hidden. */
    fun isHidden(emoji: String): Boolean {
        if (emoji in hiddenEmojis) return true
        // For ZWJ sequences and skin-tone variants, also check the base (first token before space)
        val base = emoji.substringBefore(" ")
        return base != emoji && base in hiddenEmojis
    }

    fun hide(emoji: String, context: Context) {
        if (!hiddenEmojis.add(emoji)) return
        persist(context)
    }

    fun unhide(emoji: String, context: Context) {
        if (!hiddenEmojis.remove(emoji)) return
        persist(context)
    }

    fun restoreAll(context: Context) {
        if (hiddenEmojis.isEmpty()) return
        hiddenEmojis.clear()
        persist(context)
    }

    fun getHidden(): Set<String> = hiddenEmojis.toSet()

    fun count(): Int = hiddenEmojis.size

    private fun persist(context: Context) {
        context.prefs().edit {
            putString(Settings.PREF_HIDDEN_EMOJIS, hiddenEmojis.joinToString(SEPARATOR))
        }
        // Reload the keyboard so the change is reflected immediately
        runCatching { KeyboardSwitcher.getInstance().setThemeNeedsReload() }
    }
}
