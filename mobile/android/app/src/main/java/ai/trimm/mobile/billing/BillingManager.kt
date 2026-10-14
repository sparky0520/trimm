package ai.trimm.mobile.billing

import ai.trimm.mobile.BuildConfig
import ai.trimm.mobile.network.supabase
import android.app.Activity
import android.content.Context
import android.util.Log
import com.revenuecat.purchases.CustomerInfo
import com.revenuecat.purchases.Package
import com.revenuecat.purchases.PurchaseParams
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.PurchasesConfiguration
import com.revenuecat.purchases.PurchasesTransactionException
import com.revenuecat.purchases.awaitLogIn
import com.revenuecat.purchases.awaitLogOut
import com.revenuecat.purchases.awaitOfferings
import com.revenuecat.purchases.awaitPurchase
import com.revenuecat.purchases.awaitRestore
import com.revenuecat.purchases.interfaces.UpdatedCustomerInfoListener
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

@Serializable
data class CreditCostConfig(
    @SerialName("CHAT_COMPLETION") val CHAT_COMPLETION: Int? = 2,
    @SerialName("BGM_GENERATION") val BGM_GENERATION: Int? = 5,
    @SerialName("TRANSCRIPTION") val TRANSCRIPTION: Int? = 3,
    @SerialName("SFX_FETCH") val SFX_FETCH: Int? = 1
) {
    val chatCost: Int get() = CHAT_COMPLETION ?: 2
    val bgmCost: Int get() = BGM_GENERATION ?: 5
    val transcriptionCost: Int get() = TRANSCRIPTION ?: 3
    val sfxCost: Int get() = SFX_FETCH ?: 1
}

@Serializable
data class CreditBalanceResponse(
    val userId: String? = null,
    val balance: Int,
    val lifetimePurchased: Int? = 0,
    val lifetimeSpent: Int? = 0,
    val costs: CreditCostConfig? = null
)

@Serializable
data class CreditTransactionItem(
    val id: String,
    val amount: Int,
    val balance_after: Int,
    val type: String,
    val description: String? = null,
    val created_at: String
)

@Serializable
private data class TransactionHistoryResponse(
    val transactions: List<CreditTransactionItem>
)

data class CreditPackage(
    val id: String,
    val name: String,
    val credits: Int,
    val priceString: String,
    val badge: String? = null,
    val description: String,
    val rcPackage: Package? = null
)

object BillingManager {
    private const val TAG = "BillingManager"
    const val PACKAGE_100 = "ai.ctrlb.credits.100"
    const val PACKAGE_300 = "ai.ctrlb.credits.300"
    const val PACKAGE_1000 = "ai.ctrlb.credits.1000"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val scope = CoroutineScope(Dispatchers.Main)

    private val _balance = MutableStateFlow(0)
    val balance = _balance.asStateFlow()

    private val _lifetimePurchased = MutableStateFlow(0)
    val lifetimePurchased = _lifetimePurchased.asStateFlow()

    private val _lifetimeSpent = MutableStateFlow(0)
    val lifetimeSpent = _lifetimeSpent.asStateFlow()

    private val _costs = MutableStateFlow(CreditCostConfig())
    val costs = _costs.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    private val _isPurchasing = MutableStateFlow(false)
    val isPurchasing = _isPurchasing.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage = _errorMessage.asStateFlow()

    private val _packages = MutableStateFlow(defaultPackages())
    val packages = _packages.asStateFlow()

    private val _transactions = MutableStateFlow<List<CreditTransactionItem>>(emptyList())
    val transactions = _transactions.asStateFlow()

    private val _isStorePresented = MutableStateFlow(false)
    val isStorePresented = _isStorePresented.asStateFlow()

    private var isConfigured = false

    private fun defaultPackages(): List<CreditPackage> = listOf(
        CreditPackage(
            id = PACKAGE_100,
            name = "Starter",
            credits = 100,
            priceString = "$4.99",
            badge = null,
            description = "50 AI edits or ~33 video transcriptions"
        ),
        CreditPackage(
            id = PACKAGE_300,
            name = "Creator",
            credits = 300,
            priceString = "$11.99",
            badge = "MOST POPULAR",
            description = "150 AI edits with transcriptions & projects"
        ),
        CreditPackage(
            id = PACKAGE_1000,
            name = "Pro Studio",
            credits = 1000,
            priceString = "$29.99",
            badge = "BEST VALUE",
            description = "500 AI edits with priority processing"
        )
    )

    fun setStorePresented(presented: Boolean) {
        _isStorePresented.value = presented
    }

    fun configure(context: Context) {
        if (isConfigured) return

        val apiKey = BuildConfig.REVENUECAT_API_KEY
        if (apiKey.isNotBlank() && !apiKey.contains("PLACEHOLDER", ignoreCase = true)) {
            Purchases.configure(
                PurchasesConfiguration.Builder(context.applicationContext, apiKey).build()
            )
            Purchases.sharedInstance.updatedCustomerInfoListener = UpdatedCustomerInfoListener {
                scope.launch { fetchCredits() }
            }
            Log.i(TAG, "RevenueCat configured successfully.")
        } else {
            Log.i(TAG, "RevenueCat running in development/local mode with default packages.")
        }

        isConfigured = true
        scope.launch {
            fetchOfferings()
            fetchCredits()
        }
    }

    fun updateUser(userId: String?) {
        scope.launch {
            if (!userId.isNullOrEmpty()) {
                if (Purchases.isConfigured) {
                    runCatching {
                        Purchases.sharedInstance.awaitLogIn(userId)
                    }.onFailure {
                        Log.w(TAG, "RevenueCat awaitLogIn error: ${it.message}")
                    }
                }
                fetchCredits()
                fetchOfferings()
            } else {
                if (Purchases.isConfigured) {
                    runCatching {
                        Purchases.sharedInstance.awaitLogOut()
                    }
                }
                _balance.value = 0
                _transactions.value = emptyList()
            }
        }
    }

    suspend fun fetchCredits() {
        val token = runCatching { supabase.auth.currentAccessTokenOrNull() }.getOrNull()
        if (token.isNullOrEmpty()) return

        val backendUrl = BuildConfig.PLANNER_URL.removeSuffix("/v1").trimEnd('/')
        val request = Request.Builder()
            .url("$backendUrl/v1/billing/credits")
            .get()
            .addHeader("Authorization", "Bearer $token")
            .build()

        withContext(Dispatchers.IO) {
            for (attempt in 0..1) {
                try {
                    val response = httpClient.newCall(request).execute()
                    if (response.isSuccessful) {
                        val bodyString = response.body?.string() ?: return@withContext
                        val res = json.decodeFromString<CreditBalanceResponse>(bodyString)
                        _balance.value = res.balance
                        _lifetimePurchased.value = res.lifetimePurchased ?: 0
                        _lifetimeSpent.value = res.lifetimeSpent ?: 0
                        res.costs?.let { _costs.value = it }
                        return@withContext
                    } else {
                        Log.w(TAG, "fetchCredits returned status: ${response.code}")
                        if (attempt == 0 && response.code >= 500) {
                            delay(1000)
                            continue
                        }
                        return@withContext
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "fetchCredits attempt $attempt failed: ${e.message}")
                    if (attempt == 0) {
                        delay(1000)
                        continue
                    }
                    Log.e(TAG, "fetchCredits failed after retries: ${e.message}", e)
                }
            }
        }
    }

    suspend fun fetchHistory() {
        val token = runCatching { supabase.auth.currentAccessTokenOrNull() }.getOrNull()
        if (token.isNullOrEmpty()) return

        val backendUrl = BuildConfig.PLANNER_URL.removeSuffix("/v1").trimEnd('/')
        val request = Request.Builder()
            .url("$backendUrl/v1/billing/history")
            .get()
            .addHeader("Authorization", "Bearer $token")
            .build()

        withContext(Dispatchers.IO) {
            for (attempt in 0..1) {
                try {
                    val response = httpClient.newCall(request).execute()
                    if (response.isSuccessful) {
                        val bodyString = response.body?.string() ?: return@withContext
                        val res = json.decodeFromString<TransactionHistoryResponse>(bodyString)
                        _transactions.value = res.transactions
                        return@withContext
                    } else {
                        Log.w(TAG, "fetchHistory returned status: ${response.code}")
                        if (attempt == 0 && response.code >= 500) {
                            delay(1000)
                            continue
                        }
                        return@withContext
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "fetchHistory attempt $attempt failed: ${e.message}")
                    if (attempt == 0) {
                        delay(1000)
                        continue
                    }
                    Log.e(TAG, "fetchHistory failed after retries: ${e.message}", e)
                }
            }
        }
    }

    suspend fun fetchOfferings() {
        if (!Purchases.isConfigured) return

        try {
            val offerings = Purchases.sharedInstance.awaitOfferings()
            val current = offerings.current ?: return
            val updated = current.availablePackages.map { pkg ->
                val id = pkg.product.id
                val (name, credits, badge, desc) = when {
                    id.contains("1000") -> Quad(
                        "Pro Studio", 1000, "BEST VALUE", "500 AI edits with priority processing"
                    )
                    id.contains("300") -> Quad(
                        "Creator", 300, "MOST POPULAR", "150 AI edits with transcriptions & projects"
                    )
                    else -> Quad(
                        "Starter", 100, null, "50 AI edits or ~33 video transcriptions"
                    )
                }
                CreditPackage(
                    id = id,
                    name = name,
                    credits = credits,
                    priceString = pkg.product.price.formatted,
                    badge = badge,
                    description = desc,
                    rcPackage = pkg
                )
            }.sortedBy { it.credits }

            if (updated.isNotEmpty()) {
                _packages.value = updated
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchOfferings failed: ${e.message}", e)
        }
    }

    suspend fun purchase(activity: Activity, pkg: CreditPackage) {
        _isPurchasing.value = true
        _errorMessage.value = null

        try {
            if (Purchases.isConfigured && pkg.rcPackage != null) {
                val params = PurchaseParams.Builder(activity, pkg.rcPackage).build()
                Purchases.sharedInstance.awaitPurchase(params)
                delay(800)
                fetchCredits()
                fetchHistory()
            } else {
                Log.i(TAG, "Local sandbox purchase simulated for ${pkg.name}")
                delay(500)
                fetchCredits()
            }
        } catch (e: PurchasesTransactionException) {
            if (e.userCancelled) {
                // User cancelled, ignore
            } else {
                _errorMessage.value = e.localizedMessage ?: "Purchase failed"
                throw e
            }
        } catch (e: Exception) {
            _errorMessage.value = e.localizedMessage ?: "Purchase failed"
            throw e
        } finally {
            _isPurchasing.value = false
        }
    }

    suspend fun restorePurchases() {
        if (!Purchases.isConfigured) return
        _isLoading.value = true
        _errorMessage.value = null
        try {
            Purchases.sharedInstance.awaitRestore()
            fetchCredits()
        } catch (e: Exception) {
            _errorMessage.value = e.localizedMessage ?: "Failed to restore purchases"
        } finally {
            _isLoading.value = false
        }
    }

    private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
}
