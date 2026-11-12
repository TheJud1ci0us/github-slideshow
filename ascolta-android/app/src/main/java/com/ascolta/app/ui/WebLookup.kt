package com.ascolta.app.ui

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.net.Uri

/** Opens a word or sentence in Google Translate or a Google search (the app if installed, else the browser). */
internal object WebLookup {
    private const val TRANSLATE_APP = "com.google.android.apps.translate"

    fun translate(context: Context, text: String, from: String, to: String) {
        val app = Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain").setPackage(TRANSLATE_APP)
            .putExtra(Intent.EXTRA_PROCESS_TEXT, text).putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
        if (start(context, app)) return
        val url = Uri.parse("https://translate.google.com/").buildUpon()
            .appendQueryParameter("sl", from).appendQueryParameter("tl", to)
            .appendQueryParameter("text", text).appendQueryParameter("op", "translate").build()
        start(context, Intent(Intent.ACTION_VIEW, url))
    }

    fun search(context: Context, text: String) {
        if (start(context, Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, text))) return
        start(context, Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search").buildUpon().appendQueryParameter("q", text).build()))
    }

    private fun start(context: Context, intent: Intent): Boolean = runCatching {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    }.getOrDefault(false)
}
