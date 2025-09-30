package helium314.keyboard.latin.utils

import android.content.SharedPreferences
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings

object LayoutModeHelper {
    const val MODE_ANDROID = "android"
    const val MODE_IOS = "ios"
    
    fun getLayoutMode(prefs: SharedPreferences): String {
        return prefs.getString(Settings.PREF_KEYBOARD_LAYOUT_MODE, Defaults.PREF_KEYBOARD_LAYOUT_MODE) ?: MODE_ANDROID
    }
    
    fun isAndroidMode(prefs: SharedPreferences): Boolean {
        return getLayoutMode(prefs) == MODE_ANDROID
    }
    
    fun isIOSMode(prefs: SharedPreferences): Boolean {
        return getLayoutMode(prefs) == MODE_IOS
    }
    
    fun getLayoutNameForMode(baseName: String, layoutType: LayoutType, prefs: SharedPreferences): String {
        if (isIOSMode(prefs)) {
            return when (layoutType) {
                LayoutType.FUNCTIONAL -> "${baseName}_ios"
                LayoutType.SYMBOLS -> "${baseName}_ios"
                LayoutType.MORE_SYMBOLS -> "${baseName}_ios"
                else -> baseName
            }
        }
        return baseName
    }
    
    fun getFunctionalLayoutName(isTablet: Boolean, prefs: SharedPreferences): String {
        return if (isIOSMode(prefs)) {
            if (isTablet) "functional_keys_tablet_ios" else "functional_keys_ios"
        } else {
            if (isTablet) "functional_keys_tablet" else "functional_keys"
        }
    }
    
    fun getSymbolsLayoutName(prefs: SharedPreferences): String {
        return if (isIOSMode(prefs)) "symbols_ios" else "symbols"
    }
    
    fun getMoreSymbolsLayoutName(prefs: SharedPreferences): String {
        return if (isIOSMode(prefs)) "symbols_shifted_ios" else "symbols_shifted"
    }
    
    fun shouldShowEmojiKey(prefs: SharedPreferences): Boolean {
        return if (isIOSMode(prefs)) {
            true
        } else {
            prefs.getBoolean(Settings.PREF_SHOW_EMOJI_KEY, Defaults.PREF_SHOW_EMOJI_KEY)
        }
    }
    
    fun shouldShowLanguageSwitchKey(prefs: SharedPreferences): Boolean {
        return prefs.getBoolean(Settings.PREF_SHOW_LANGUAGE_SWITCH_KEY, Defaults.PREF_SHOW_LANGUAGE_SWITCH_KEY)
    }
}