package com.ascolta.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.ascolta.app.ui.AscoltaRoot
import com.ascolta.app.ui.theme.AscoltaTheme
import kotlinx.coroutines.launch

/** AppCompatActivity so the app language can be changed in Settings (per-app language). */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        graph.player.connect()
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            AscoltaTheme {
                AscoltaRoot()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val type = intent.type ?: ""
        when {
            type.startsWith("audio/") -> {
                val uri = intent.data
                    ?: IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    ?: return
                lifecycleScope.launch {
                    runCatching { graph.repo.importAudio(uri) }
                        .onSuccess { Toast.makeText(this@MainActivity, getString(R.string.imported_transcribing), Toast.LENGTH_SHORT).show() }
                        .onFailure { Toast.makeText(this@MainActivity, getString(R.string.import_failed, it.message ?: ""), Toast.LENGTH_LONG).show() }
                }
            }
            intent.action == Intent.ACTION_SEND && type == "text/plain" -> {
                intent.getStringExtra(Intent.EXTRA_TEXT)?.let { graph.pendingSharedText.value = it }
            }
        }
    }
}
