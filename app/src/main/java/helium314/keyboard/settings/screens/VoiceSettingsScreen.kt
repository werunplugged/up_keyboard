// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.getActivity
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.settings.SettingsWithoutKey
import helium314.keyboard.settings.Theme
import helium314.keyboard.settings.initPreview
import helium314.keyboard.settings.previewDark
import helium314.keyboard.settings.preferences.SwitchPreference

@Composable
fun VoiceSettingsScreen(
    onClickBack: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = context.prefs()
    val b = (context.getActivity() as? SettingsActivity)?.prefChanged?.collectAsState()
    if ((b?.value ?: 0) < 0)
        Log.v("irrelevant", "stupid way to trigger recomposition on preference change")
    
    val items = listOf(
        R.string.voice_input_category,
        Settings.PREF_ENABLE_VOICE_INPUT,
        if (prefs.getBoolean(Settings.PREF_ENABLE_VOICE_INPUT, Defaults.PREF_ENABLE_VOICE_INPUT))
            Settings.PREF_USE_BUILTIN_VOICE_RECOGNITION else null,
        SettingsWithoutKey.VOICE_PERMISSION_STATUS
    )
    
    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.voice_input_category),
        settings = items
    )
}

fun createVoiceSettings(context: Context) = listOf(
    Setting(context, Settings.PREF_ENABLE_VOICE_INPUT, 
        R.string.enable_voice_input, R.string.enable_voice_input_summary) {
        SwitchPreference(it, Defaults.PREF_ENABLE_VOICE_INPUT)
    },
    Setting(context, Settings.PREF_USE_BUILTIN_VOICE_RECOGNITION,
        R.string.use_builtin_voice_recognition, R.string.use_builtin_voice_recognition_summary) {
        SwitchPreference(it, Defaults.PREF_USE_BUILTIN_VOICE_RECOGNITION)
    },
    Setting(context, SettingsWithoutKey.VOICE_PERMISSION_STATUS, R.string.voice_permission_status) { setting ->
        VoicePermissionStatus(setting)
    }
)

@Composable
fun VoicePermissionStatus(setting: Setting) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) 
                == PackageManager.PERMISSION_GRANTED
        )
    }
    
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasPermission = isGranted
    }
    
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = setting.title,
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = if (hasPermission) 
                        stringResource(R.string.voice_permission_granted)
                    else 
                        stringResource(R.string.voice_permission_denied),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (hasPermission) 
                        MaterialTheme.colorScheme.primary
                    else 
                        MaterialTheme.colorScheme.error
                )
            }
            
            if (!hasPermission) {
                Button(
                    onClick = {
                        // Try to request permission
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    modifier = Modifier.padding(start = 8.dp)
                ) {
                    Text(stringResource(R.string.voice_grant_permission))
                }
            } else {
                Icon(
                    painter = painterResource(R.drawable.ic_check_circle),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
        
        // If permission was denied and can't be requested again, show settings option
        if (!hasPermission) {
            val shouldShowRationale = (context.getActivity() as? android.app.Activity)
                ?.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) == false
            
            if (shouldShowRationale) {
                Text(
                    text = "Permission must be granted in system settings",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .clickable {
                            // Open app settings
                            val intent = Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            intent.data = Uri.fromParts("package", context.packageName, null)
                            context.startActivity(intent)
                        }
                )
            }
        }
    }
}

@Preview
@Composable
private fun VoiceSettingsScreenPreview() {
    initPreview(LocalContext.current)
    Surface(modifier = Modifier.padding(4.dp)) {
        VoiceSettingsScreen {}
    }
}

@Preview
@Composable
private fun VoiceSettingsScreenPreviewDark() {
    initPreview(LocalContext.current)
    Theme(previewDark) {
        Surface(modifier = Modifier.padding(4.dp)) {
            VoiceSettingsScreen {}
        }
    }
}