// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.collection.LruCache
import androidx.compose.foundation.Image
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import helium314.keyboard.keyboard.emoji.HiddenEmojis
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.BackButton

private data class EmojiCategoryData(val nameRes: Int, val iconRes: Int, val emojis: List<String>)

private data class CategoryDef(val nameRes: Int, val file: String, val iconRes: Int)

private val categoryDefs = listOf(
    CategoryDef(R.string.category_smileys_emotion, "SMILEYS_AND_EMOTION.txt", R.drawable.ic_emoji_smileys_emotion),
    CategoryDef(R.string.category_people_body, "PEOPLE_AND_BODY.txt", R.drawable.ic_emoji_people_body),
    CategoryDef(R.string.category_animals_nature, "ANIMALS_AND_NATURE.txt", R.drawable.ic_emoji_animals_nature),
    CategoryDef(R.string.category_food_drink, "FOOD_AND_DRINK.txt", R.drawable.ic_emoji_food_drink),
    CategoryDef(R.string.category_travel_places, "TRAVEL_AND_PLACES.txt", R.drawable.ic_emoji_travel_places),
    CategoryDef(R.string.category_activities, "ACTIVITIES.txt", R.drawable.ic_emoji_activities),
    CategoryDef(R.string.category_objects, "OBJECTS.txt", R.drawable.ic_emoji_objects),
    CategoryDef(R.string.category_symbols, "SYMBOLS.txt", R.drawable.ic_emoji_symbols),
    CategoryDef(R.string.category_flags, "FLAGS.txt", R.drawable.ic_emoji_flags),
    CategoryDef(R.string.category_emoticons, "EMOTICONS.txt", R.drawable.ic_emoji_emoticons),
)

/**
 * Pattern matching directional ZWJ sequences appended to a base emoji, e.g. 🚶‍➡️ (walking
 * facing right), 🧎‍⬅️ (kneeling facing left), 🚀‍⬆️, plus 🙂‍↔️ and 🙂‍↕️ (Unicode 15.1
 * head-shake/nod). The six arrow code points covered are ➡ U+27A1, ⬅ U+2B05, ⬆ U+2B06,
 * ⬇ U+2B07, ↔ U+2194, ↕ U+2195.
 */
private val directionalSuffix = Regex("\\u200D[\\u27A1\\u2B05\\u2B06\\u2B07\\u2194\\u2195]\\uFE0F?$")

/**
 * Removes VS-16 (U+FE0F) from an emoji string. Noto Color Emoji's ligature substitutions
 * are written without VS-16 in their input pattern, so leaving VS-16 in the rendered
 * string prevents ZWJ sequences (judge, pilot, couple, family, broken-chain, etc.) from
 * matching their ligature — the result is each codepoint drawn separately. VS-16 is
 * purely a presentation hint; the font defaults full emoji ZWJ sequences to color.
 */
private fun stripVariationSelector16(s: String): String =
    if ('️' in s) s.replace("️", "") else s

/**
 * Process-wide cache of pre-rendered emoji bitmaps. Compose `Text` does a full text
 * layout / shaping pass per call, which becomes the dominant cost when rendering a few
 * hundred cells against a 25 MB color emoji font. The keyboard's emoji panel sidesteps
 * this entirely by drawing each emoji once via `Canvas.drawText` and reusing the result;
 * we mirror that here. Each emoji is rendered to a small bitmap on first access (key:
 * `emoji + sizePx`) and then displayed via [Image], which is just a blit.
 *
 * Capacity covers the largest single category comfortably plus headroom across tabs.
 */
private val emojiBitmapCache = LruCache<String, ImageBitmap>(4000)

private fun renderEmojiBitmap(emoji: String, sizePx: Int, paint: Paint): ImageBitmap {
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val fm = paint.fontMetrics
    val tw = paint.measureText(emoji)
    val x = (sizePx - tw) / 2f
    val y = sizePx / 2f - (fm.ascent + fm.descent) / 2f
    canvas.drawText(emoji, x, y, paint)
    return bitmap.asImageBitmap()
}

/**
 * Loads a single category's emoji list from assets. Done lazily on first display so
 * we don't pay the parse cost for all 10 categories on screen entry.
 *
 * For non-emoticon categories:
 *  - Directional ZWJ duplicates (🚶, 🚶‍➡️, 🚶‍⬅️ etc.) are collapsed to the base.
 *  - VS-16 (U+FE0F) is stripped so Noto Color Emoji's ligature substitutions fire.
 *  - [helium314.keyboard.keyboard.emoji.SupportedEmojis] filtering matches what the
 *    keyboard itself displays.
 */
private fun loadCategory(context: Context, def: CategoryDef): EmojiCategoryData {
    val isEmoticons = (def.file == "EMOTICONS.txt")
    val emojis = runCatching {
        val raw = context.assets.open("emoji/${def.file}").reader().use { it.readLines() }
            .map { if (isEmoticons) it else it.trim() }
            .filter { it.isNotEmpty() && (isEmoticons || !it.startsWith("//")) }
            .map { if (isEmoticons) it else it.substringBefore(" ") }
        if (isEmoticons) {
            raw // emoticons are plain ASCII art, leave untouched
        } else {
            val seen = HashSet<String>()
            raw.mapNotNull { e ->
                val canonical = stripVariationSelector16(e.replace(directionalSuffix, ""))
                if (!seen.add(canonical)) return@mapNotNull null
                if (helium314.keyboard.keyboard.emoji.SupportedEmojis.isUnsupported(canonical)) return@mapNotNull null
                canonical
            }
        }
    }.getOrDefault(emptyList())
    return EmojiCategoryData(def.nameRes, def.iconRes, emojis)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmojiManagementScreen(onClickBack: () -> Unit) {
    val ctx = LocalContext.current

    var editMode by remember { mutableStateOf(false) }
    var showHidden by remember { mutableStateOf(false) }

    // Cause recomposition when the hidden set mutates so the grid filters update.
    var hiddenVersion by remember { mutableIntStateOf(0) }
    val isHidden: (String) -> Boolean = remember(hiddenVersion) { { e -> HiddenEmojis.isHidden(e) } }
    val hiddenCount = remember(hiddenVersion) { HiddenEmojis.count() }

    if (showHidden) {
        HiddenEmojisScreen(
            onClickBack = { showHidden = false },
            onChange = { hiddenVersion++ }
        )
        return
    }

    // Per-tab cache so a category is parsed only once per visit. The HorizontalPager only
    // composes pages near the current page (default offscreenLimit = 0), so an unvisited
    // category is never loaded. Tapping a tab or swiping triggers parsing for just that
    // category right before its page is composed.
    val categoryCache = remember { mutableMapOf<Int, EmojiCategoryData>() }
    fun categoryAt(index: Int): EmojiCategoryData =
        categoryCache.getOrPut(index) { loadCategory(ctx, categoryDefs[index]) }

    val pagerState = rememberPagerState(pageCount = { categoryDefs.size })
    val coroutineScope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.emoji_management)) },
                navigationIcon = { BackButton { onClickBack() } },
                actions = {
                    IconButton(onClick = { editMode = !editMode }) {
                        Icon(
                            painter = painterResource(
                                if (editMode) R.drawable.ic_check_circle else R.drawable.ic_edit
                            ),
                            contentDescription = stringResource(
                                if (editMode) R.string.dialog_close else R.string.emoji_management
                            )
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = if (editMode) stringResource(R.string.emoji_management_edit_hint)
                               else stringResource(R.string.emoji_management_hidden_count, hiddenCount),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    if (!editMode) {
                        TextButton(onClick = { showHidden = true }) {
                            Text(stringResource(R.string.emoji_management_view_hidden))
                        }
                    }
                }
            }

            // Tab bar follows the pager's current page. Tapping a tab animates the pager
            // there; the user can also swipe horizontally to change tabs.
            ScrollableTabRow(
                selectedTabIndex = pagerState.currentPage,
                edgePadding = 0.dp,
                containerColor = MaterialTheme.colorScheme.surface,
            ) {
                categoryDefs.forEachIndexed { idx, def ->
                    Tab(
                        selected = pagerState.currentPage == idx,
                        onClick = {
                            coroutineScope.launch { pagerState.animateScrollToPage(idx) }
                        },
                        icon = {
                            Icon(
                                painter = painterResource(def.iconRes),
                                contentDescription = stringResource(def.nameRes),
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    )
                }
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                key = { it }
            ) { page ->
                val cat = remember(page) { categoryAt(page) }
                val visibleEmojis = remember(cat, hiddenVersion) {
                    cat.emojis.filterNot { isHidden(it) }
                }
                if (visibleEmojis.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize().padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(cat.nameRes),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 48.dp),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
                    ) {
                        items(
                            items = visibleEmojis,
                            key = { it }
                        ) { emoji ->
                            EmojiCell(
                                emoji = emoji,
                                editMode = editMode,
                                onClick = {
                                    if (editMode) {
                                        HiddenEmojis.hide(emoji, ctx)
                                        hiddenVersion++
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HiddenEmojisScreen(onClickBack: () -> Unit, onChange: () -> Unit) {
    val ctx = LocalContext.current
    val hiddenList = remember { mutableStateListOf<String>().apply { addAll(HiddenEmojis.getHidden()) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.emoji_management_hidden_title)) },
                navigationIcon = { BackButton { onClickBack() } },
                actions = {
                    if (hiddenList.isNotEmpty()) {
                        TextButton(onClick = {
                            HiddenEmojis.restoreAll(ctx)
                            hiddenList.clear()
                            onChange()
                        }) {
                            Text(stringResource(R.string.emoji_management_restore_all))
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (hiddenList.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        stringResource(R.string.emoji_management_no_hidden),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                    Text(
                        text = stringResource(R.string.emoji_management_restore_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 48.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp)
                ) {
                    items(items = hiddenList.toList(), key = { it }) { emoji ->
                        EmojiCell(
                            emoji = emoji,
                            editMode = false,
                            onClick = {
                                HiddenEmojis.unhide(emoji, ctx)
                                hiddenList.remove(emoji)
                                onChange()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmojiCell(emoji: String, editMode: Boolean, onClick: () -> Unit) {
    val density = LocalDensity.current
    // Render at 1.5x display density so the bitmap looks sharp on high-DPI screens, but
    // capped at 96 px so cache memory stays bounded for thousands of emojis.
    val sizePx = with(density) { 32.dp.roundToPx() }.coerceAtMost(96)
    val paint = remember(sizePx) {
        Paint().apply {
            isAntiAlias = true
            textSize = sizePx * 0.85f
            helium314.keyboard.keyboard.KeyboardTypeface.emojiTypeface()?.let { typeface = it }
        }
    }
    val cacheKey = "${sizePx}|${emoji}"
    val bitmap = remember(cacheKey) {
        emojiBitmapCache.get(cacheKey) ?: run {
            val b = renderEmojiBitmap(emoji, sizePx, paint)
            emojiBitmapCache.put(cacheKey, b)
            b
        }
    }
    Box(
        modifier = Modifier
            .size(48.dp)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = Modifier.size(32.dp)
        )
        if (editMode) {
            // Small ❌ overlay in the top-right corner indicating tap-to-hide.
            // We use an Icon (vector drawable) instead of a Text "×" because the glyph's
            // baseline metrics push it visually below center; a vector centers reliably.
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.error),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_close),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onError,
                    modifier = Modifier.size(10.dp)
                )
            }
        }
    }
}

private sealed interface GridItem {
    data class Header(val nameRes: Int) : GridItem
    data class Emoji(val value: String) : GridItem
}
