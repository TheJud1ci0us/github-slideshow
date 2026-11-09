package com.ascolta.app.data

import android.content.Context
import android.os.Build
import com.ascolta.app.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Anonymous usage statistics, only with the user's consent (asked once; Settings → Privacy).
 *
 * What is sent: which features are used (e.g. "transcription started with SenseVoice for
 * Japanese", "flashcard reviewed: Good", "sleep timer 30 min"), speeds, app version, Android
 * version and phone model. Never sent: podcast or episode names, feed addresses, search
 * text, transcripts, saved words, or anything typed. Users are a random ID made on the phone,
 * hashed with a salt before it leaves it, so they can't be identified or linked to anything.
 *
 * Signals go to TelemetryDeck (privacy-first analytics, EU), whose dashboard shows them.
 * Without an App ID in the build, nothing is ever collected or sent.
 */
object Analytics {
    private const val ENDPOINT = "https://nom.telemetrydeck.com/v2/"
    private const val MAX_QUEUE = 500
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private lateinit var app: Context
    private lateinit var settings: Settings
    private lateinit var http: OkHttpClient
    private lateinit var queueFile: File
    private val sessionId = UUID.randomUUID().toString()
    private val time = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

    /** Whether this build can send analytics at all (an App ID was configured). */
    val available: Boolean get() = BuildConfig.TELEMETRYDECK_APP_ID.isNotBlank()

    private val enabled: Boolean get() = available && ::settings.isInitialized && settings.analyticsConsent.value == CONSENT_YES

    const val CONSENT_YES = "yes"
    const val CONSENT_NO = "no"

    fun init(context: Context, settings: Settings, http: OkHttpClient) {
        app = context.applicationContext
        this.settings = settings
        this.http = http
        queueFile = File(context.filesDir, "analytics-queue.json")
        // Send what is queued now and then, and when the app goes to the background.
        scope.launch {
            while (true) {
                delay(60_000)
                flush()
            }
        }
    }

    /** Records a signal (dropped unless the user agreed). [params] must never hold personal data. */
    fun track(type: String, params: Map<String, String> = emptyMap(), value: Double? = null) {
        if (!enabled) return
        val payload = defaultPayload() + params
        val signal = Signal(
            receivedAt = time.format(Date()),
            appID = BuildConfig.TELEMETRYDECK_APP_ID,
            clientUser = userHash(),
            sessionID = sessionId,
            type = type,
            payload = payload,
            isTestMode = BuildConfig.DEBUG.toString(),
            floatValue = value,
        )
        scope.launch {
            lock.withLock {
                val queue = (readQueue() + signal).takeLast(MAX_QUEUE)
                runCatching { queueFile.writeText(json.encodeToString(queue)) }
            }
        }
    }

    /** Sends queued signals; kept for later if offline. */
    fun flush() {
        if (!enabled) {
            if (::queueFile.isInitialized && settings.analyticsConsent.value == CONSENT_NO) queueFile.delete()
            return
        }
        scope.launch {
            lock.withLock {
                val queue = readQueue()
                if (queue.isEmpty()) return@withLock
                val body = json.encodeToString(queue).toRequestBody("application/json".toMediaType())
                val ok = runCatching {
                    http.newCall(Request.Builder().url(ENDPOINT).post(body).build()).execute().use { it.isSuccessful }
                }.getOrDefault(false)
                if (ok) queueFile.delete()
            }
        }
    }

    /** Called when the user changes their choice; "no" also deletes anything not yet sent. */
    fun setConsent(agree: Boolean) {
        settings.analyticsConsent.set(if (agree) CONSENT_YES else CONSENT_NO)
        if (agree) {
            track("Analytics.consentGiven")
            track("TelemetryDeck.Session.started")
        } else {
            runCatching { queueFile.delete() }
            // A new random ID next time, so earlier statistics can't be linked to later ones.
            app.getSharedPreferences("analytics", Context.MODE_PRIVATE).edit().remove("id").apply()
        }
    }

    private fun readQueue(): List<Signal> =
        runCatching { json.decodeFromString<List<Signal>>(queueFile.readText()) }.getOrDefault(emptyList())

    private fun userHash(): String {
        val prefs = app.getSharedPreferences("analytics", Context.MODE_PRIVATE)
        val id = prefs.getString("id", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("id", it).apply() }
        return MessageDigest.getInstance("SHA-256").digest((id + BuildConfig.TELEMETRYDECK_SALT).toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private fun defaultPayload(): Map<String, String> {
        val version = runCatching { app.packageManager.getPackageInfo(app.packageName, 0) }.getOrNull()
        return mapOf(
            "TelemetryDeck.AppInfo.version" to (version?.versionName ?: "?"),
            "TelemetryDeck.AppInfo.buildNumber" to (if (Build.VERSION.SDK_INT >= 28) version?.longVersionCode?.toString() else null).orEmpty(),
            "TelemetryDeck.Device.platform" to "Android",
            "TelemetryDeck.Device.operatingSystem" to "Android",
            "TelemetryDeck.Device.systemVersion" to "Android ${Build.VERSION.RELEASE}",
            "TelemetryDeck.Device.systemMajorVersion" to Build.VERSION.RELEASE.substringBefore('.'),
            "TelemetryDeck.Device.brand" to Build.BRAND,
            "TelemetryDeck.Device.modelName" to Build.MODEL,
            "TelemetryDeck.RunContext.locale" to Locale.getDefault().toLanguageTag(),
            "Ascolta.learning" to settings.learningLanguage.value,
            "Ascolta.native" to settings.nativeLanguage.value,
            "Ascolta.languagesLearned" to settings.learningList().size.toString(),
        )
    }

    @Serializable
    private data class Signal(
        val receivedAt: String,
        val appID: String,
        val clientUser: String,
        val sessionID: String,
        val type: String,
        val payload: Map<String, String>,
        val isTestMode: String,
        val floatValue: Double? = null,
    )
}
