// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.voice.transcribe

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ComposeView
import helium314.keyboard.settings.Theme

/** Activity that handles ACTION_SEND for audio MIME types, transcribes using Whisper. */
class TranscribeActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        var audioUri: Uri? = null
        if (intent?.action == Intent.ACTION_SEND) {
            audioUri = intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }

        Log.d(TAG, "Transcribe activity started, uri=$audioUri")

        val composeView = ComposeView(this)
        setContentView(composeView)
        val uri = audioUri
        composeView.setContent {
            Theme {
                TranscribeScreen(audioUri = uri, onDismiss = { finish() })
            }
        }
    }

    companion object {
        private const val TAG = "TranscribeActivity"
    }
}
