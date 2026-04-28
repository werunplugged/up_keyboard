// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.keyboard

import android.content.Context
import android.graphics.Typeface
import android.widget.TextView
import androidx.compose.ui.text.font.FontFamily
import helium314.keyboard.latin.common.isEmoji
import helium314.keyboard.latin.settings.Settings

object KeyboardTypeface {
    private val lock = Any()

    private var cachedCustomTypeface: Typeface? = null
    private var cachedCustomFontFamily: FontFamily? = null
    @Volatile
    private var customTypefaceLoaded = false

    private var cachedEmojiTypeface: Typeface? = null
    private var cachedEmojiFontFamily: FontFamily? = null
    @Volatile
    private var emojiTypefaceLoaded = false

    private fun loadCustomTypeface(context: Context): Typeface? {
        return runCatching {
            Typeface.createFromFile(Settings.getCustomFontFile(context))
        }.getOrNull()
    }

    private fun loadCustomEmojiTypeface(context: Context): Typeface? {
        return runCatching {
            Typeface.createFromFile(Settings.getCustomEmojiFontFile(context))
        }.getOrNull()
    }

    /** Path of the bundled fallback emoji font inside [android.content.res.AssetManager]. */
    private const val BUNDLED_EMOJI_FONT_ASSET = "fonts/NotoColorEmoji.ttf"

    /**
     * Loads the bundled Noto Color Emoji asset shipped with the app. Used as the default
     * emoji typeface so newer ZWJ sequences (e.g. 🍄‍🟫, ⛓️‍💥, 🧑‍🧑‍🧒) render correctly even
     * on devices whose system emoji font is older than the asset files we ship.
     */
    private fun loadBundledEmojiTypeface(context: Context): Typeface? {
        return runCatching {
            Typeface.createFromAsset(context.assets, BUNDLED_EMOJI_FONT_ASSET)
        }.getOrNull()
    }

    @JvmStatic
    fun customTypeface(): Typeface? {
        if (customTypefaceLoaded) return cachedCustomTypeface
        val context = Settings.getCurrentContext() ?: return null
        synchronized(lock) {
            if (!customTypefaceLoaded) {
                cachedCustomTypeface = loadCustomTypeface(context)
                cachedCustomFontFamily = cachedCustomTypeface?.let(::FontFamily)
                customTypefaceLoaded = true
            }
            return cachedCustomTypeface
        }
    }

    @JvmStatic
    fun emojiTypeface(): Typeface? {
        if (emojiTypefaceLoaded) return cachedEmojiTypeface
        val context = Settings.getCurrentContext() ?: return null
        synchronized(lock) {
            if (!emojiTypefaceLoaded) {
                // Prefer a user-supplied custom emoji font; otherwise fall back to the
                // bundled Noto Color Emoji asset so all emojis (including newer ZWJ
                // sequences) render correctly out of the box.
                val typeface = loadCustomEmojiTypeface(context)
                    ?: loadBundledEmojiTypeface(context)
                cachedEmojiTypeface = typeface
                cachedEmojiFontFamily = typeface?.let(::FontFamily)
                emojiTypefaceLoaded = true
            }
            return cachedEmojiTypeface
        }
    }

    /** Compose-side accessor; returns [FontFamily] wrapping [emojiTypeface]. */
    @JvmStatic
    fun emojiFontFamily(): FontFamily? {
        if (!emojiTypefaceLoaded) emojiTypeface()
        return cachedEmojiFontFamily
    }

    @JvmStatic
    fun customFontFamily(): FontFamily? {
        if (!customTypefaceLoaded) customTypeface()
        return cachedCustomFontFamily
    }

    @JvmStatic
    fun resolve(
        text: CharSequence?,
        defaultTypeface: Typeface = Typeface.DEFAULT,
    ): Typeface {
        val emojiTypeface = emojiTypeface()
        return if (emojiTypeface != null && text != null && isEmoji(text)) {
            emojiTypeface
        } else {
            customTypeface() ?: defaultTypeface
        }
    }

    @JvmStatic
    fun applyToTextView(textView: TextView) {
        applyToTextView(textView, textView.text, Typeface.DEFAULT)
    }

    @JvmStatic
    fun applyToTextView(textView: TextView, text: CharSequence?, defaultTypeface: Typeface) {
        textView.typeface = resolve(text, defaultTypeface = defaultTypeface)
    }

    @JvmStatic
    fun clearCache() {
        synchronized(lock) {
            cachedCustomTypeface = null
            cachedCustomFontFamily = null
            customTypefaceLoaded = false
            cachedEmojiTypeface = null
            cachedEmojiFontFamily = null
            emojiTypefaceLoaded = false
        }
    }
}
