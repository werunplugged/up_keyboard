// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.voice.transcribe

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.R
import helium314.keyboard.voice.recognition.VoiceRecognitionEngine
import helium314.keyboard.voice.recognition.WhisperRecognitionEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class TranscribeState { IDLE, DECODING, TRANSCRIBING, DONE, ERROR }

private val WHISPER_LANGUAGES = listOf(
    "Auto" to "",
    "English" to "en", "Chinese" to "zh", "German" to "de", "Spanish" to "es",
    "Russian" to "ru", "Korean" to "ko", "French" to "fr", "Japanese" to "ja",
    "Portuguese" to "pt", "Turkish" to "tr", "Polish" to "pl", "Catalan" to "ca",
    "Dutch" to "nl", "Arabic" to "ar", "Swedish" to "sv", "Italian" to "it",
    "Indonesian" to "id", "Hindi" to "hi", "Finnish" to "fi", "Vietnamese" to "vi",
    "Hebrew" to "he", "Ukrainian" to "uk", "Greek" to "el", "Malay" to "ms",
    "Czech" to "cs", "Romanian" to "ro", "Danish" to "da", "Hungarian" to "hu",
    "Tamil" to "ta", "Norwegian" to "no", "Thai" to "th", "Urdu" to "ur",
    "Croatian" to "hr", "Bulgarian" to "bg", "Lithuanian" to "lt", "Latin" to "la",
    "Maori" to "mi", "Malayalam" to "ml", "Welsh" to "cy", "Slovak" to "sk",
    "Telugu" to "te", "Persian" to "fa", "Latvian" to "lv", "Bengali" to "bn",
    "Serbian" to "sr", "Azerbaijani" to "az", "Slovenian" to "sl", "Kannada" to "kn",
    "Estonian" to "et", "Macedonian" to "mk", "Breton" to "br", "Basque" to "eu",
    "Icelandic" to "is", "Armenian" to "hy", "Nepali" to "ne", "Mongolian" to "mn",
    "Bosnian" to "bs", "Kazakh" to "kk", "Albanian" to "sq", "Swahili" to "sw",
    "Galician" to "gl", "Marathi" to "mr", "Punjabi" to "pa", "Sinhala" to "si",
    "Khmer" to "km", "Shona" to "sn", "Yoruba" to "yo", "Somali" to "so",
    "Afrikaans" to "af", "Occitan" to "oc", "Georgian" to "ka", "Belarusian" to "be",
    "Tajik" to "tg", "Sindhi" to "sd", "Gujarati" to "gu", "Amharic" to "am",
    "Yiddish" to "yi", "Lao" to "lo", "Uzbek" to "uz", "Faroese" to "fo",
    "Haitian Creole" to "ht", "Pashto" to "ps", "Turkmen" to "tk", "Nynorsk" to "nn",
    "Maltese" to "mt", "Sanskrit" to "sa", "Luxembourgish" to "lb", "Myanmar" to "my",
    "Tibetan" to "bo", "Tagalog" to "tl", "Malagasy" to "mg", "Assamese" to "as",
    "Tatar" to "tt", "Hawaiian" to "haw", "Lingala" to "ln", "Hausa" to "ha",
    "Bashkir" to "ba", "Javanese" to "jw", "Sundanese" to "su"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranscribeScreen(audioUri: Uri?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var state by remember { mutableStateOf(TranscribeState.IDLE) }
    var resultText by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    var selectedLanguage by remember { mutableStateOf(WHISPER_LANGUAGES[0]) }
    var showLanguageDialog by remember { mutableStateOf(false) }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

    // Start decoding + transcription
    LaunchedEffect(audioUri) {
        if (audioUri == null) {
            errorMessage = context.getString(R.string.transcribe_no_audio)
            state = TranscribeState.ERROR
            return@LaunchedEffect
        }
        state = TranscribeState.DECODING
        try {
            val samples = withContext(Dispatchers.IO) {
                AudioDecoder.decode(context, audioUri)
            }
            if (samples.isEmpty()) {
                errorMessage = context.getString(R.string.transcribe_no_audio)
                state = TranscribeState.ERROR
                return@LaunchedEffect
            }
            state = TranscribeState.TRANSCRIBING
            withContext(Dispatchers.IO) {
                val engine = WhisperRecognitionEngine(context)
                if (!engine.initialize()) {
                    withContext(Dispatchers.Main) {
                        errorMessage = context.getString(R.string.voice_engine_unavailable)
                        state = TranscribeState.ERROR
                    }
                    return@withContext
                }
                engine.setListener(object : VoiceRecognitionEngine.RecognitionListener {
                    override fun onRecognitionStarted() {}
                    override fun onPartialResult(text: String?) {
                        text?.let { resultText = it }
                    }
                    override fun onRecognitionResult(text: String?, language: String?) {
                        resultText = text ?: ""
                        state = TranscribeState.DONE
                    }
                    override fun onRecognitionError(error: String?) {
                        errorMessage = error ?: context.getString(R.string.voice_recognition_error)
                        state = TranscribeState.ERROR
                    }
                    override fun onRecognitionFinished() {
                        if (state == TranscribeState.TRANSCRIBING) {
                            state = TranscribeState.DONE
                        }
                    }
                })
                val lang = selectedLanguage.second.ifEmpty { null }
                engine.recognize(samples, lang)
            }
        } catch (e: Exception) {
            errorMessage = context.getString(R.string.transcribe_unsupported_format)
            state = TranscribeState.ERROR
        }
    }

    // Language picker dialog
    if (showLanguageDialog) {
        AlertDialog(
            onDismissRequest = { showLanguageDialog = false },
            title = { Text(stringResource(R.string.transcribe_select_language)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()).heightIn(max = 400.dp)) {
                    WHISPER_LANGUAGES.forEach { lang ->
                        Text(
                            text = lang.first,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedLanguage = lang
                                    showLanguageDialog = false
                                }
                                .padding(vertical = 12.dp, horizontal = 8.dp),
                            color = if (lang == selectedLanguage)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            },
            confirmButton = {}
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (expanded) Modifier.fillMaxHeight() else Modifier)
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Title
            Text(
                text = stringResource(R.string.transcribe_share_label),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            // Language chip
            AssistChip(
                onClick = { showLanguageDialog = true },
                label = { Text(selectedLanguage.first) },
                modifier = Modifier.padding(bottom = 16.dp)
            )

            // Progress / result
            when (state) {
                TranscribeState.IDLE, TranscribeState.DECODING -> {
                    CircularProgressIndicator(modifier = Modifier.padding(16.dp))
                    Text(stringResource(R.string.transcribe_decoding))
                }
                TranscribeState.TRANSCRIBING -> {
                    CircularProgressIndicator(modifier = Modifier.padding(16.dp))
                    Text(stringResource(R.string.transcribe_transcribing))
                    if (resultText.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = resultText,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = if (expanded) Int.MAX_VALUE else 5,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                        )
                        AnimatedVisibility(!expanded && resultText.length > 300) {
                            TextButton(onClick = { expanded = true }) {
                                Text(stringResource(R.string.transcribe_show_more))
                            }
                        }
                    }
                }
                TranscribeState.DONE -> {
                    Text(
                        stringResource(R.string.transcribe_complete),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Text(
                        text = resultText,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = if (expanded) Int.MAX_VALUE else 8,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                    )
                    AnimatedVisibility(!expanded && resultText.length > 300) {
                        TextButton(onClick = { expanded = true }) {
                            Text(stringResource(R.string.transcribe_show_more))
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("transcription", resultText))
                        Toast.makeText(context, context.getString(R.string.toast_msg_clipboard_copy), Toast.LENGTH_SHORT).show()
                    }) {
                        Text(stringResource(R.string.transcribe_copy))
                    }
                }
                TranscribeState.ERROR -> {
                    Text(
                        text = errorMessage,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }
    }
}
