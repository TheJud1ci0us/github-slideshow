package com.ascolta.app.data

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import com.ascolta.app.BuildConfig
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.IntegrityTokenRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * LingoPod Pro through Google Play: the three products, buying, restoring, and whether this
 * device has Pro. Pro is granted only by a pass signed by LingoPod's purchase-check server
 * (which asks Google about the purchase and checks Play Integrity), never by a local flag.
 * Builds without a server address (development) trust Play's local answer instead.
 */
class Billing(private val context: Context, private val http: OkHttpClient, private val scope: CoroutineScope) : PurchasesUpdatedListener {
    companion object {
        const val MONTHLY = "pro_monthly"
        const val YEARLY = "pro_yearly"
        const val LIFETIME = "pro_lifetime"
        private val SUBS = listOf(MONTHLY, YEARLY)
    }

    private val prefs = context.getSharedPreferences("pro", Context.MODE_PRIVATE)
    /** A random ID made by the app (not a hardware ID), tying a pass to this install. */
    val device: String = prefs.getString("device", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("device", it).apply() }

    private val _pro = MutableStateFlow(checkPass(prefs.getString("pass", null)))
    val isPro: StateFlow<Boolean> = _pro

    /** The plans with their prices in the user's currency (empty until Play answers). */
    val products = MutableStateFlow<List<ProductDetails>>(emptyList())
    /** A message for the paywall (e.g. "Google Play isn't available"). */
    val problem = MutableStateFlow<String?>(null)
    /** Requests to show the paywall (from free limits anywhere in the app), with the reason. */
    val paywallRequests = MutableSharedFlow<String>(extraBufferCapacity = 4)

    private val client = BillingClient.newBuilder(context)
        .setListener(this)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .build()

    private fun checkPass(token: String?): Boolean =
        ProPass.check(token, BuildConfig.PRO_PASS_PUBLIC_KEY, context.packageName, device, System.currentTimeMillis())?.pro == true

    fun start() {
        if (client.isReady) return
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                    problem.value = result.debugMessage.ifBlank { "Google Play billing unavailable (${result.responseCode})" }
                    return
                }
                problem.value = null
                scope.launch { loadProducts(); refresh() }
            }
            override fun onBillingServiceDisconnected() {}
        })
    }

    private suspend fun loadProducts() {
        val subs = client.queryProductDetails(QueryProductDetailsParams.newBuilder().setProductList(
            SUBS.map { QueryProductDetailsParams.Product.newBuilder().setProductId(it).setProductType(BillingClient.ProductType.SUBS).build() },
        ).build()).productDetailsList.orEmpty()
        val once = client.queryProductDetails(QueryProductDetailsParams.newBuilder().setProductList(
            listOf(QueryProductDetailsParams.Product.newBuilder().setProductId(LIFETIME).setProductType(BillingClient.ProductType.INAPP).build()),
        ).build()).productDetailsList.orEmpty()
        products.value = (subs + once).sortedBy { listOf(MONTHLY, YEARLY, LIFETIME).indexOf(it.productId) }
    }

    /** Re-reads the purchases from Play (restores Pro on a new phone) and asks the server. */
    suspend fun refresh() {
        if (!client.isReady) return
        val subs = client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()).purchasesList
        val once = client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()).purchasesList
        handle(subs.map { it to "subs" } + once.map { it to "inapp" })
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        if (result.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            scope.launch { handle(purchases.map { it to if (it.products.any { p -> p in SUBS }) "subs" else "inapp" }) }
        }
    }

    private suspend fun handle(purchases: List<Pair<Purchase, String>>) {
        val bought = purchases.filter { it.first.purchaseState == Purchase.PurchaseState.PURCHASED }
        // Purchases must be acknowledged within 3 days or Google refunds them.
        for ((p, _) in bought) if (!p.isAcknowledged) {
            runCatching { client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(p.purchaseToken).build()) }
        }
        if (BuildConfig.PRO_SERVER_URL.isBlank()) {
            // Development builds without the server: trust Play's local answer.
            _pro.value = bought.isNotEmpty()
            return
        }
        if (bought.isEmpty()) {
            // Nothing bought (or refunded): keep a still-valid pass only if offline; Play says no purchase.
            prefs.edit().remove("pass").apply()
            _pro.value = false
            return
        }
        runCatching { verifyWithServer(bought) }
            .onSuccess { token ->
                prefs.edit().putString("pass", token).apply()
                _pro.value = checkPass(token)
            }
            .onFailure { AppLog.w("Billing", "Purchase check failed", it) } // offline: the stored pass still counts until it expires
    }

    /** Sends the purchases and a Play Integrity token to the server; returns the signed pass. */
    private suspend fun verifyWithServer(bought: List<Pair<Purchase, String>>): String {
        val nonce = android.util.Base64.encodeToString(ByteArray(24).also { java.security.SecureRandom().nextBytes(it) },
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
        val integrity = IntegrityManagerFactory.create(context).requestIntegrityToken(
            IntegrityTokenRequest.builder().setNonce(nonce).setCloudProjectNumber(BuildConfig.CLOUD_PROJECT_NUMBER).build(),
        ).await().token()
        val body = JSONObject()
            .put("integrityToken", integrity)
            .put("nonce", nonce)
            .put("device", device)
            .put("purchases", JSONArray().apply {
                bought.forEach { (p, type) -> p.products.forEach { id -> put(JSONObject().put("productId", id).put("token", p.purchaseToken).put("type", type)) } }
            })
        return withContext(Dispatchers.IO) {
            http.newCall(Request.Builder().url(BuildConfig.PRO_SERVER_URL.trimEnd('/') + "/")
                .post(body.toString().toRequestBody("application/json".toMediaType())).build()).execute().use { r ->
                val json = JSONObject(r.body?.string().orEmpty())
                if (!r.isSuccessful) error(json.optString("error", "HTTP ${r.code}"))
                json.getString("pass") + "." + json.getString("sig")
            }
        }
    }

    /** Opens Google Play's purchase sheet for [product] (subscriptions: their first plan). */
    fun buy(activity: Activity, product: ProductDetails): Boolean {
        val params = BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(product).apply {
            product.subscriptionOfferDetails?.firstOrNull()?.offerToken?.let { setOfferToken(it) }
        }.build()
        val result = client.launchBillingFlow(activity, BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(params))
            .setObfuscatedAccountId(device)
            .build())
        return result.responseCode == BillingClient.BillingResponseCode.OK
    }

    /** Asks the app to show the paywall (from a free limit). */
    fun requestPaywall(reason: String) { paywallRequests.tryEmit(reason) }
}
