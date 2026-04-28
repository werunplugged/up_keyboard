// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.internal.keyboard_parser

import android.content.Context
import helium314.keyboard.keyboard.Key
import helium314.keyboard.keyboard.Key.KeyParams
import helium314.keyboard.keyboard.KeyboardId
import helium314.keyboard.keyboard.emoji.HiddenEmojis
import helium314.keyboard.keyboard.emoji.SupportedEmojis
import helium314.keyboard.keyboard.internal.KeyboardParams
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.common.StringUtils
import helium314.keyboard.latin.common.splitOnWhitespace
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.ResourceUtils
import helium314.keyboard.latin.utils.prefs
import java.util.Collections
import kotlin.let
import kotlin.math.sqrt

class EmojiParser(private val params: KeyboardParams, private val context: Context) {

    fun parse(): ArrayList<ArrayList<KeyParams>> {
        val emojiFileName = getEmojiFileName(params.mId.mElementId)
        // Emoticons are plain ASCII text (e.g. ":-)", "¯\_(ツ)_/¯"), not emoji glyphs —
        // skip the unsupported-glyph and directional-dedupe filters or we'd remove all of them.
        val isEmoticons = params.mId.mElementId == KeyboardId.ELEMENT_EMOJI_CATEGORY10
        val rawLines = if (emojiFileName == null) {
            listOf( // special template keys for recents category
                StringUtils.newSingleCodePointString(Constants.RECENTS_TEMPLATE_KEY_CODE_0),
                StringUtils.newSingleCodePointString(Constants.RECENTS_TEMPLATE_KEY_CODE_1),
            )
        } else {
            loadEmojiFile(emojiFileName, context)
        }
        // Strip VS-16 (U+FE0F) from emoji strings — Noto Color Emoji's GSUB ligature
        // patterns are written without VS-16, so leaving it in prevents the ligature from
        // matching and the engine renders each codepoint separately (causing 🧑‍⚖️ to look
        // like a person plus scales side-by-side, etc.). VS-16 is purely a presentation
        // hint anyway and the font defaults full emoji ZWJ sequences to color presentation.
        val emojiLines = if (isEmoticons) rawLines else rawLines.map { stripVariationSelector16(it) }
        if (params.mId.mElementId == KeyboardId.ELEMENT_EMOJI_CATEGORY2) {
            loadEmojiDefaultVersionsAndPopupSpecs(context, emojiLines)
            return parseEmojis(emojiLines.map { line -> getEmojiDefaultVersion(line.splitOnWhitespace().first()) }, isEmoticons)
        }
        return parseEmojis(emojiLines, isEmoticons)
    }

    private fun parseEmojis(emojis: List<String>, isEmoticons: Boolean): ArrayList<ArrayList<KeyParams>> {
        val row = ArrayList<KeyParams>(emojis.size)
        var currentX = params.mLeftPadding.toFloat()
        val currentY = params.mTopPadding.toFloat() // no need to ever change, assignment to rows into rows is done in DynamicGridKeyboard

        val (keyWidth, keyHeight) = getEmojiKeyDimensions(params, context)

        // Dedupe directional ZWJ duplicates (🚶, 🚶‍➡️, 🚶‍⬅️, etc.) for non-emoticon categories.
        val source = if (isEmoticons) emojis else dedupeDirectionalDuplicates(emojis)
        source.forEach { emoji ->
            val keyParams = parseEmojiKeyNew(emoji, isEmoticons) ?: return@forEach
            keyParams.xPos = currentX
            keyParams.yPos = currentY
            keyParams.mAbsoluteWidth = keyWidth
            keyParams.mAbsoluteHeight = keyHeight
            currentX += keyParams.mAbsoluteWidth
            row.add(keyParams)
        }
        return arrayListOf(row)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun parseEmojiKeyNew(emoji: String, isEmoticons: Boolean): KeyParams? {
        if (SupportedEmojis.isUnsupported(emoji)) return null
        if (HiddenEmojis.isHidden(emoji)) return null
        // We intentionally do NOT filter by Paint.hasGlyph here. Newer ZWJ sequences such
        // as 🍄‍🟫 (brown mushroom) or ⛓️‍💥 (broken chain) won't render as a single glyph on
        // older Android system fonts, but the user can install a current emoji font via
        // Settings → Appearance → Set custom emoji font from file to fix the rendering.
        // Filtering here would hide these emojis altogether, which is worse than showing
        // them imperfectly.
        return KeyParams(
            emoji,
            emoji.getCode(),
            if (emojiPopupSpecs[emoji] != null) EMOJI_HINT_LABEL else null,
            emojiPopupSpecs[emoji],
            Key.LABEL_FLAGS_FONT_NORMAL,
            params
        )
    }
}

/**
 * Pattern matching ZWJ + directional arrow + optional VS-16 at end of an emoji.
 * Covers ➡ U+27A1, ⬅ U+2B05, ⬆ U+2B06, ⬇ U+2B07 (walking/running directions) plus
 * ↔ U+2194 and ↕ U+2195 (the Unicode 15.1 head-shake/head-nod smiley variants).
 */
private val directionalSuffix = Regex("\\u200D[\\u27A1\\u2B05\\u2B06\\u2B07\\u2194\\u2195]\\uFE0F?$")

/** Removes VS-16 (U+FE0F) from a string. See call sites for rationale. */
private fun stripVariationSelector16(s: String): String =
    if ('️' in s) s.replace("️", "") else s

/** Collapses lines like 🚶, 🚶‍➡️, 🚶‍⬅️ down to one (🚶) keeping the first occurrence. */
private fun dedupeDirectionalDuplicates(lines: List<String>): List<String> {
    val seen = HashSet<String>()
    return lines.mapNotNull { line ->
        // Use the leading emoji of the line for the canonical comparison; preserve the
        // original line content (which may include skin-tone variants after a space) for
        // popup spec lookups elsewhere.
        val head = line.splitOnWhitespace().firstOrNull() ?: return@mapNotNull null
        val canonical = head.replace(directionalSuffix, "")
        if (seen.add(canonical)) line else null
    }
}


fun getEmojiKeyDimensions(params: KeyboardParams, context: Context): Pair<Float, Float> {
    // determine key width for default settings (no number row, no one-handed mode, 100% height and bottom padding scale)
    // this is a bit long, but ensures that emoji size stays the same, independent of these settings
    // we also ignore side padding for key width, and prefer fewer keys per row over narrower keys
    val defaultKeyWidth = ResourceUtils.getDefaultKeyboardWidth(context) * params.mDefaultKeyWidth
    var keyWidth = defaultKeyWidth * sqrt(Settings.getValues().mKeyboardHeightScale)
    val defaultKeyboardHeight = ResourceUtils.getDefaultKeyboardHeight(context.resources, false)
    val defaultBottomPadding = context.resources.getFraction(
        R.fraction.config_keyboard_bottom_padding_holo, defaultKeyboardHeight, defaultKeyboardHeight
    )
    val emojiKeyboardHeight = defaultKeyboardHeight * 0.75f + params.mVerticalGap - defaultBottomPadding -
        context.resources.getDimensionPixelSize(R.dimen.config_emoji_category_page_id_height)
    var keyHeight =
        emojiKeyboardHeight * params.mDefaultRowHeight * Settings.getValues().mKeyboardHeightScale // still apply height scale to key

    if (Settings.getValues().mEmojiKeyFit) {
        keyWidth *= Settings.getValues().mFontSizeMultiplierEmoji
        keyHeight *= Settings.getValues().mFontSizeMultiplierEmoji
    }
    return keyWidth to keyHeight
}

fun String.getCode(): Int =
    if (StringUtils.codePointCount(this) != 1) KeyCode.MULTIPLE_CODE_POINTS
    else Character.codePointAt(this, 0)

fun loadEmojiDefaultVersionsAndPopupSpecs(context: Context) {
    loadEmojiDefaultVersionsAndPopupSpecs(context, null)
}

private fun loadEmojiDefaultVersionsAndPopupSpecs(context: Context, category2EmojiLines: List<String>?) {
    val defaultTone = context.prefs().getString(Settings.PREF_EMOJI_SKIN_TONE, Defaults.PREF_EMOJI_SKIN_TONE)
    if (defaultSkinTone == defaultTone) {
        return
    }

    defaultSkinTone = defaultTone
    emojiDefaultVersions.clear()
    emojiNeutralVersions.clear()
    emojiPopupSpecs.clear()
    (category2EmojiLines ?: loadEmojiFile(getEmojiFileName(KeyboardId.ELEMENT_EMOJI_CATEGORY2)!!, context)).forEach { line ->
        var split = line.splitOnWhitespace()
        if (defaultSkinTone != "") {
            // adjust PEOPLE_AND_BODY if we have a non-yellow default skin tone
            // find the line containing the skin tone, and swap with first
            val foundIndex = split.indexOfFirst { it.contains(defaultSkinTone!!) }
            if (foundIndex > 0) {
                emojiDefaultVersions[split[0]] = split[foundIndex]
                emojiNeutralVersions[split[foundIndex]] = split[0]
                split = split.toMutableList()
                Collections.swap(split, 0, foundIndex)
            }
        }
        split.drop(1)
            .filterNot { SupportedEmojis.isUnsupported(it) || HiddenEmojis.isHidden(it) }
            .takeIf { it.isNotEmpty() }?.joinToString(",")?.let { emojiPopupSpecs[split.first()] = it }
    }
}

private fun getEmojiFileName(id: Int): String? {
    return when (id) {
        KeyboardId.ELEMENT_EMOJI_CATEGORY1 -> "SMILEYS_AND_EMOTION.txt"
        KeyboardId.ELEMENT_EMOJI_CATEGORY2 -> "PEOPLE_AND_BODY.txt"
        KeyboardId.ELEMENT_EMOJI_CATEGORY3 -> "ANIMALS_AND_NATURE.txt"
        KeyboardId.ELEMENT_EMOJI_CATEGORY4 -> "FOOD_AND_DRINK.txt"
        KeyboardId.ELEMENT_EMOJI_CATEGORY5 -> "TRAVEL_AND_PLACES.txt"
        KeyboardId.ELEMENT_EMOJI_CATEGORY6 -> "ACTIVITIES.txt"
        KeyboardId.ELEMENT_EMOJI_CATEGORY7 -> "OBJECTS.txt"
        KeyboardId.ELEMENT_EMOJI_CATEGORY8 -> "SYMBOLS.txt"
        KeyboardId.ELEMENT_EMOJI_CATEGORY9 -> "FLAGS.txt"
        KeyboardId.ELEMENT_EMOJI_CATEGORY10 -> "EMOTICONS.txt"
        else -> null
    }
}

private fun loadEmojiFile(emojiFileName: String, context: Context): List<String> =
    context.assets.open("emoji/$emojiFileName").reader().use { it.readLines() }

const val EMOJI_HINT_LABEL = "◥"

private var defaultSkinTone: String? = null
private val emojiDefaultVersions: MutableMap<String, String> = mutableMapOf()
private val emojiNeutralVersions: MutableMap<String, String> = mutableMapOf()
private val emojiPopupSpecs: MutableMap<String, String> = mutableMapOf()

fun getEmojiDefaultVersion(emoji: String): String = emojiDefaultVersions[emoji] ?: emoji
fun getEmojiNeutralVersion(emoji: String): String = emojiNeutralVersions[emoji] ?: emoji
fun getEmojiPopupSpec(emoji: String): String? = emojiPopupSpecs[emoji]
