package com.simpletools.inventory.core

import android.app.Activity
import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.android.billingclient.api.*
import com.simpletools.inventory.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import kotlin.coroutines.resume
import java.io.IOException

private val Context.licenseStore by preferencesDataStore("play_inventory_license")
private val proofKey = stringPreferencesKey("proof")
private val signatureKey = stringPreferencesKey("signature")
const val PRO_PRODUCT = "simpletools_inventory_pro_lifetime"

enum class PlayStatus { LOADING, READY, CONFIG, UNAVAILABLE, PENDING, CANCELED, INVALID, NO_PURCHASE }

data class PlayState(val pro: Boolean = false, val price: String? = null, val status: PlayStatus = PlayStatus.LOADING)

internal fun verifyPlaySignature(key: String, payload: String, signature: String): Boolean = runCatching {
    val decoder = Base64.getDecoder()
    val publicKey = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(decoder.decode(key)))
    Signature.getInstance("SHA1withRSA").run {
        initVerify(publicKey)
        update(payload.toByteArray(Charsets.UTF_8))
        verify(decoder.decode(signature))
    }
}.getOrDefault(false)

class PlayLicense(private val context: Context, private val scope: CoroutineScope) {
    private val mutable = MutableStateFlow(PlayState())
    val state = mutable.asStateFlow()
    private val lock = Mutex()
    private var cached = false
    private var offer: ProductDetails? = null
    private val client = BillingClient.newBuilder(context)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .setListener { result, _ ->
            when (result.responseCode) {
                BillingClient.BillingResponseCode.OK,
                BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> refresh()
                BillingClient.BillingResponseCode.USER_CANCELED -> status(PlayStatus.CANCELED)
                else -> status(PlayStatus.UNAVAILABLE)
            }
        }.build()

    private fun valid(purchase: Purchase) =
        purchase.packageName == context.packageName && PRO_PRODUCT in purchase.products &&
            purchase.purchaseState == Purchase.PurchaseState.PURCHASED && purchase.purchaseToken.isNotBlank() &&
            verifyPlaySignature(BuildConfig.PLAY_PUBLIC_KEY, purchase.originalJson, purchase.signature)

    fun refresh(explicit: Boolean = false) {
        scope.launch {
            lock.withLock {
                try {
                    synchronize(explicit)
                } catch (_: IOException) {
                    status(PlayStatus.UNAVAILABLE)
                }
            }
        }
    }

    private suspend fun synchronize(explicit: Boolean) {
        if (BuildConfig.PLAY_PUBLIC_KEY.isBlank()) {
            status(PlayStatus.CONFIG)
            return
        }
        if (!cached) {
            val prefs = context.licenseStore.data.first()
            val proof = runCatching { Purchase(prefs[proofKey].orEmpty(), prefs[signatureKey].orEmpty()) }.getOrNull()
            mutable.value = mutable.value.copy(pro = proof?.let(::valid) == true)
            cached = true
        }
        offer = null
        mutable.value = mutable.value.copy(price = null, status = PlayStatus.LOADING)
        if (!connected()) {
            status(PlayStatus.UNAVAILABLE)
            return
        }
        val (result, purchases) = suspendCancellableCoroutine { continuation ->
            client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()) {
                response, owned -> continuation.resume(response to owned)
            }
        }
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            status(PlayStatus.UNAVAILABLE)
            return
        }
        val relevant = purchases.filter { PRO_PRODUCT in it.products }
        val owned = relevant.firstOrNull(::valid)
        val pending = relevant.any { it.purchaseState == Purchase.PurchaseState.PENDING }
        val invalid = relevant.any { it.purchaseState == Purchase.PurchaseState.PURCHASED && !valid(it) }
        val acknowledged = owned != null && (owned.isAcknowledged || acknowledge(owned))
        mutable.value = mutable.value.copy(pro = acknowledged)
        context.licenseStore.edit { prefs ->
            if (owned != null && acknowledged) {
                prefs[proofKey] = owned.originalJson
                prefs[signatureKey] = owned.signature
            } else {
                prefs.remove(proofKey)
                prefs.remove(signatureKey)
            }
        }
        val product = QueryProductDetailsParams.Product.newBuilder()
            .setProductId(PRO_PRODUCT).setProductType(BillingClient.ProductType.INAPP).build()
        offer = suspendCancellableCoroutine { continuation ->
            client.queryProductDetailsAsync(QueryProductDetailsParams.newBuilder().setProductList(listOf(product)).build()) {
                response, details ->
                continuation.resume(if (response.responseCode == BillingClient.BillingResponseCode.OK)
                    details.productDetailsList.firstOrNull { it.productId == PRO_PRODUCT } else null)
            }
        }
        val price = offer?.oneTimePurchaseOfferDetails?.formattedPrice
        mutable.value = PlayState(acknowledged, price, when {
            acknowledged -> PlayStatus.READY
            owned != null -> PlayStatus.UNAVAILABLE
            invalid -> PlayStatus.INVALID
            pending -> PlayStatus.PENDING
            explicit -> PlayStatus.NO_PURCHASE
            price == null -> PlayStatus.UNAVAILABLE
            else -> PlayStatus.READY
        })
    }

    fun buy(activity: Activity) {
        val product = offer ?: return refresh()
        if (state.value.pro || state.value.status !in setOf(PlayStatus.READY, PlayStatus.NO_PURCHASE, PlayStatus.CANCELED)) return
        val oneTime = product.oneTimePurchaseOfferDetails ?: return
        val details = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(product).setOfferToken(oneTime.offerToken).build()
        status(PlayStatus.LOADING)
        val result = client.launchBillingFlow(activity, BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(details)).build())
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> Unit
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> refresh()
            BillingClient.BillingResponseCode.USER_CANCELED -> status(PlayStatus.CANCELED)
            else -> status(PlayStatus.UNAVAILABLE)
        }
    }

    private suspend fun connected(): Boolean = client.isReady || suspendCancellableCoroutine { continuation ->
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (continuation.isActive) continuation.resume(result.responseCode == BillingClient.BillingResponseCode.OK)
            }

            override fun onBillingServiceDisconnected() = Unit
        })
    }

    private suspend fun acknowledge(purchase: Purchase): Boolean = suspendCancellableCoroutine { continuation ->
        client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build()) {
            continuation.resume(it.responseCode == BillingClient.BillingResponseCode.OK)
        }
    }

    private fun status(status: PlayStatus) { mutable.value = mutable.value.copy(status = status) }

    fun close() = client.endConnection()
}
