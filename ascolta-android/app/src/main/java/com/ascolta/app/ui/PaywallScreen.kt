package com.ascolta.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.android.billingclient.api.ProductDetails
import com.ascolta.app.R
import com.ascolta.app.data.Billing
import com.ascolta.app.data.FreeLimits
import com.ascolta.app.graph
import kotlinx.coroutines.launch

/** LingoPod Pro: what it adds, the three plans with Google's prices, buy and restore. */
@Composable
fun PaywallScreen(reason: String?, onBack: () -> Unit, openLegal: () -> Unit) {
    val context = LocalContext.current
    val graph = context.graph
    val billing = graph.billing
    val scope = rememberCoroutineScope()
    val pro by billing.isPro.collectAsState()
    val products by billing.products.collectAsState()
    val problem by billing.problem.collectAsState()
    var chosen by remember { mutableStateOf(Billing.YEARLY) }
    LaunchedEffect(Unit) { billing.start() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenTitle(stringResource(R.string.pro_title), stringResource(R.string.pro_subtitle), onBack = onBack)
        // Why the paywall opened (a free limit), when it did.
        reasonText(reason)?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        }
        SectionCard {
            listOf(
                R.string.pro_feature_minutes, R.string.pro_feature_best, R.string.pro_feature_cards,
                R.string.pro_feature_practice, R.string.pro_feature_check, R.string.pro_feature_anki, R.string.pro_feature_stats,
            ).forEach { f ->
                Row(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(f), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        if (pro) {
            Text(stringResource(R.string.pro_active), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(24.dp))
        } else {
            if (products.isEmpty()) {
                Text(problem ?: stringResource(R.string.pro_loading), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(20.dp))
            }
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                products.forEach { p ->
                    val selected = p.productId == chosen
                    Column(
                        Modifier.fillMaxWidth()
                            .border(if (selected) 2.dp else 1.dp,
                                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                MaterialTheme.shapes.large)
                            .clickable { chosen = p.productId }
                            .padding(16.dp),
                    ) {
                        Text(stringResource(planName(p.productId)), style = MaterialTheme.typography.titleMedium)
                        Text(price(p), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            PillButton(
                stringResource(R.string.pro_continue),
                enabled = products.any { it.productId == chosen },
                onClick = { products.firstOrNull { it.productId == chosen }?.let { p -> context.findActivity()?.let { billing.buy(it, p) } } },
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Text(stringResource(R.string.pro_terms), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        }
        Row(Modifier.padding(horizontal = 8.dp)) {
            TextButton(onClick = { scope.launch { billing.start(); billing.refresh() } }) { Text(stringResource(R.string.pro_restore)) }
            TextButton(onClick = openLegal) { Text(stringResource(R.string.pro_legal)) }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun reasonText(reason: String?): String? = when (reason) {
    "transcription" -> stringResource(R.string.free_minutes_used)
    "cards" -> stringResource(R.string.free_cards_used, FreeLimits.MAX_CARDS)
    "practice" -> stringResource(R.string.free_practice_used, FreeLimits.DAILY_PRACTICE)
    "best_model", "check", "anki", "statistics" -> stringResource(R.string.pro_feature)
    else -> null
}

private fun planName(id: String) = when (id) {
    Billing.MONTHLY -> R.string.pro_monthly
    Billing.YEARLY -> R.string.pro_yearly
    else -> R.string.pro_lifetime
}

/** Google's localised price ("€29.99 / year"). */
private fun price(p: ProductDetails): String =
    p.subscriptionOfferDetails?.firstOrNull()?.pricingPhases?.pricingPhaseList?.lastOrNull()?.formattedPrice
        ?: p.oneTimePurchaseOfferDetails?.formattedPrice ?: ""

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
