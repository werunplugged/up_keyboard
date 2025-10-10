// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.content.Context
import android.os.Build
import android.view.inputmethod.InputMethodSubtype
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.Constants.Separators
import helium314.keyboard.latin.common.Constants.Subtype.ExtraValue
import helium314.keyboard.latin.common.LocaleUtils
import helium314.keyboard.latin.common.LocaleUtils.constructLocale
import helium314.keyboard.latin.common.LocaleUtils.localizedDisplayName
import helium314.keyboard.latin.common.splitOnWhitespace
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.latin.utils.DictionaryInfoUtils
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.MissingDictionaryDialog
import helium314.keyboard.latin.utils.SubtypeLocaleUtils
import helium314.keyboard.latin.utils.SubtypeLocaleUtils.displayName
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.SubtypeUtilsAdditional
import helium314.keyboard.latin.utils.getActivity
import helium314.keyboard.latin.utils.locale
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.SearchScreen
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.settings.SettingsDestination
import helium314.keyboard.settings.Theme
import helium314.keyboard.settings.initPreview
import helium314.keyboard.settings.previewDark
import helium314.keyboard.settings.screens.getUserAndInternalDictionaries
import java.util.Locale

@Composable
fun LanguageScreen(
    onClickBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val prefs = ctx.prefs()
    val b = (LocalContext.current.getActivity() as? SettingsActivity)?.prefChanged?.collectAsState()
    if ((b?.value ?: 0) < 0)
        Log.v("irrelevant", "stupid way to trigger recomposition on preference change")

    // Toggle state for system language sync
    var useSystemLanguages by remember {
        mutableStateOf(prefs.getBoolean(Settings.PREF_USE_SYSTEM_LANGUAGES, Defaults.PREF_USE_SYSTEM_LANGUAGES))
    }

    // State for showing missing dictionary dialog
    var showMissingDictDialog by remember { mutableStateOf(false) }
    var pendingSubtypeToggle by remember { mutableStateOf<InputMethodSubtype?>(null) }

    // State for manual mode
    var sortedSubtypes by remember { mutableStateOf(if (!useSystemLanguages) getSortedSubtypes(ctx) else emptyList()) }

    // Reload system locales when screen appears (for system mode)
    var refreshTrigger by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        SubtypeSettings.reloadSystemLocales(ctx)
        refreshTrigger++
    }

    // Make system locales reactive to changes (for system mode)
    val systemLocales = remember(refreshTrigger, b?.value) {
        SubtypeSettings.getSystemLocales()
    }

    // System mode: Group system languages by locale and find their active layout
    val languageItems = remember(systemLocales, refreshTrigger, useSystemLanguages) {
        if (!useSystemLanguages) return@remember emptyList()
        systemLocales.mapNotNull { locale ->
            val availableSubtypes = SubtypeSettings.getResourceSubtypesForLocale(locale)
            if (availableSubtypes.isEmpty()) return@mapNotNull null

            val enabledSubtype = SubtypeSettings.getEnabledSubtypes().firstOrNull { it.locale() == locale }
                ?: availableSubtypes.first()

            LanguageItem(locale, enabledSubtype)
        }
    }

    // Toggle item for switching modes
    val toggleItem = object {
        override fun toString() = "TOGGLE_SYSTEM_LANGUAGES"
    }

    // System mode: Add manage button as second item (after toggle)
    val manageButtonItem = object {
        override fun toString() = "MANAGE_SYSTEM_LANGUAGES"
    }

    // All items: toggle first, then mode-specific items
    val allItemsList = if (useSystemLanguages) {
        listOf(toggleItem, manageButtonItem) + languageItems
    } else {
        listOf(toggleItem) + sortedSubtypes
    }

    SearchScreen(
        onClickBack = onClickBack,
        title = {
            Text(stringResource(R.string.language_and_layouts_title))
        },
        filteredItems = { term ->
            if (term.isBlank()) {
                allItemsList
            } else if (useSystemLanguages) {
                // System mode filtering (keep toggle, filter languages)
                listOf(toggleItem) + languageItems.filter {
                    it.locale.localizedDisplayName(ctx.resources).replace("(", "")
                        .splitOnWhitespace().any { word -> word.startsWith(term, true) }
                }
            } else {
                // Manual mode filtering (keep toggle, filter subtypes)
                listOf(toggleItem) + sortedSubtypes.filter {
                    it.displayName().replace("(", "")
                        .splitOnWhitespace().any { it.startsWith(term, true) }
                }
            }
        },
        itemContent = { item ->
            when (item) {
                toggleItem -> {
                    // Toggle preference item
                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val newValue = !useSystemLanguages
                                    useSystemLanguages = newValue
                                    prefs.edit {
                                        putBoolean(Settings.PREF_USE_SYSTEM_LANGUAGES, newValue)
                                        if (newValue) {
                                            // Switching TO system mode - clear preferences
                                            putString(Settings.PREF_ENABLED_SUBTYPES, Defaults.PREF_ENABLED_SUBTYPES)
                                            putString(Settings.PREF_SELECTED_SUBTYPE, Defaults.PREF_SELECTED_SUBTYPE)
                                        } else {
                                            // Switching TO manual mode - use the actual resource subtypes to preserve their properties
                                            val currentSystemSubtypes = SubtypeSettings.getSystemLocales().mapNotNull { locale ->
                                                SubtypeSettings.getResourceSubtypesForLocale(locale).firstOrNull()
                                            }
                                            // Convert to SettingsSubtype format for storage, preserving all properties
                                            val settingsSubtypes = currentSystemSubtypes.map { it.toSettingsSubtype() }
                                            putString(Settings.PREF_ENABLED_SUBTYPES, SubtypeSettings.createPrefSubtypes(settingsSubtypes))
                                            // Clear additional subtypes since we're using resource subtypes
                                            putString(Settings.PREF_ADDITIONAL_SUBTYPES, "")
                                        }
                                    }
                                    SubtypeSettings.reloadEnabledSubtypes(ctx)
                                    if (!newValue) {
                                        sortedSubtypes = getSortedSubtypes(ctx)
                                    }
                                }
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Use system languages",
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text(
                                    text = if (useSystemLanguages)
                                        "Languages sync with system settings"
                                    else
                                        "Manually manage keyboard languages",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = useSystemLanguages,
                                onCheckedChange = { checked ->
                                    useSystemLanguages = checked
                                    prefs.edit {
                                        putBoolean(Settings.PREF_USE_SYSTEM_LANGUAGES, checked)
                                        if (checked) {
                                            // Switching TO system mode - clear preferences
                                            putString(Settings.PREF_ENABLED_SUBTYPES, Defaults.PREF_ENABLED_SUBTYPES)
                                            putString(Settings.PREF_SELECTED_SUBTYPE, Defaults.PREF_SELECTED_SUBTYPE)
                                        } else {
                                            // Switching TO manual mode - use the actual resource subtypes to preserve their properties
                                            val currentSystemSubtypes = SubtypeSettings.getSystemLocales().mapNotNull { locale ->
                                                SubtypeSettings.getResourceSubtypesForLocale(locale).firstOrNull()
                                            }
                                            // Convert to SettingsSubtype format for storage, preserving all properties
                                            val settingsSubtypes = currentSystemSubtypes.map { it.toSettingsSubtype() }
                                            putString(Settings.PREF_ENABLED_SUBTYPES, SubtypeSettings.createPrefSubtypes(settingsSubtypes))
                                            // Clear additional subtypes since we're using resource subtypes
                                            putString(Settings.PREF_ADDITIONAL_SUBTYPES, "")
                                        }
                                    }
                                    SubtypeSettings.reloadEnabledSubtypes(ctx)
                                    if (!checked) {
                                        sortedSubtypes = getSortedSubtypes(ctx)
                                    }
                                }
                            )
                        }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    }
                }
                manageButtonItem -> {
                    // Manage system languages button (only in system mode)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                try {
                                    val intent = android.content.Intent(android.provider.Settings.ACTION_LOCALE_SETTINGS)
                                    intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                                    ctx.startActivity(intent)
                                } catch (e: Exception) {
                                    Log.w("LanguageScreen", "Failed to open system language settings", e)
                                }
                            }
                            .padding(vertical = 12.dp, horizontal = 16.dp)
                    ) {
                        Column {
                            Text(
                                text = "Manage system languages",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Add or remove languages in system settings",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                is LanguageItem -> {
                    // System mode language item (read-only, not clickable)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp, horizontal = 16.dp)
                    ) {
                        Column {
                            Text(
                                item.locale.localizedDisplayName(ctx.resources),
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = "Layout: ${item.subtype.displayName().substringAfter("(").substringBefore(")")}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                is InputMethodSubtype -> {
                    // Manual mode subtype item
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                SettingsDestination.navigateTo(SettingsDestination.Subtype + item.toSettingsSubtype().toPref())
                            }
                            .padding(vertical = 6.dp, horizontal = 16.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(item.displayName(), style = MaterialTheme.typography.bodyLarge)
                            val description = item.getExtraValueOf(ExtraValue.SECONDARY_LOCALES)?.split(Separators.KV)
                                ?.joinToString(", ") { it.constructLocale().localizedDisplayName(ctx.resources) }
                            if (description != null)
                                Text(
                                    text = description,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                        }
                        Switch(
                            checked = item in SubtypeSettings.getEnabledSubtypes(fallback = true),
                            onCheckedChange = { checked ->
                                if (checked) {
                                    // Check if dictionary is available when enabling
                                    if (!dictsAvailable(item.locale(), ctx)) {
                                        pendingSubtypeToggle = item
                                        showMissingDictDialog = true
                                    } else {
                                        SubtypeSettings.addEnabledSubtype(ctx, item)
                                    }
                                } else {
                                    SubtypeSettings.removeEnabledSubtype(ctx, item)
                                }
                            }
                        )
                    }
                }
            }
        }
    )

    // Show missing dictionary dialog if needed
    if (showMissingDictDialog && pendingSubtypeToggle != null) {
        MissingDictionaryDialog(
            onDismissRequest = {
                // User acknowledged the warning, enable the subtype anyway
                SubtypeSettings.addEnabledSubtype(ctx, pendingSubtypeToggle!!)
                showMissingDictDialog = false
                pendingSubtypeToggle = null
            },
            locale = pendingSubtypeToggle!!.locale()
        )
    }
}

// Check if dictionaries are available for a locale
private fun dictsAvailable(locale: Locale, context: Context): Boolean {
    val (dicts, hasInternal) = getUserAndInternalDictionaries(context, locale)
    return hasInternal || dicts.isNotEmpty()
}

private data class LanguageItem(
    val locale: Locale,
    val subtype: InputMethodSubtype
)

// Helper function for manual mode: sorts all available subtypes by priority
private fun getSortedSubtypes(context: Context): List<InputMethodSubtype> {
    val systemLocales = SubtypeSettings.getSystemLocales()
    val enabledSubtypes = SubtypeSettings.getEnabledSubtypes(fallback = true)
    val localesWithDictionary = DictionaryInfoUtils.getCacheDirectories(context).mapNotNull { dir ->
        if (!dir.isDirectory)
            return@mapNotNull null
        if (dir.list()?.any { it.endsWith(DictionaryInfoUtils.USER_DICTIONARY_SUFFIX) } == true)
            dir.name.constructLocale()
        else null
    }.orEmpty()

    val defaultAdditionalSubtypes = Defaults.PREF_ADDITIONAL_SUBTYPES.split(Separators.SETS).map {
        it.substringBefore(Separators.SET) to (it.substringAfter(Separators.SET) + ",AsciiCapable,EmojiCapable,isAdditionalSubtype")
    }
    fun isDefaultSubtype(subtype: InputMethodSubtype): Boolean =
        defaultAdditionalSubtypes.any { it.first == subtype.locale().language && it.second == subtype.extraValue }

    val subtypeSortComparator = compareBy<InputMethodSubtype>(
        { it !in enabledSubtypes },
        { it.locale() !in localesWithDictionary },
        { it.locale() !in systemLocales},
        { !(SubtypeSettings.isAdditionalSubtype(it) && !isDefaultSubtype(it) ) },
        {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) it.languageTag == SubtypeLocaleUtils.NO_LANGUAGE
            else it.locale == SubtypeLocaleUtils.NO_LANGUAGE
        },
        { it.displayName() }
    )
    return SubtypeSettings.getAllAvailableSubtypes().sortedWith(subtypeSortComparator)
}

@Preview
@Composable
private fun Preview() {
    initPreview(LocalContext.current)
    Theme(previewDark) {
        Surface {
            LanguageScreen { }
        }
    }
}
