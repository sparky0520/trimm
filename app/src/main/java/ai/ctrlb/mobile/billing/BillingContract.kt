package ai.ctrlb.mobile.billing

import java.security.MessageDigest

object BillingProducts {
    const val BASIC_MONTHLY = "ctrlb_basic_monthly"
    const val PRO_MONTHLY = "ctrlb_pro_monthly"
    val subscriptions: Set<String> = setOf(BASIC_MONTHLY, PRO_MONTHLY)
}

enum class PurchaseState {
    Pending,
    Verifying,
    Active,
    Failed,
}

data class PurchaseSubmission(
    val productId: String,
    val purchaseToken: String,
    val obfuscatedAccountId: String,
) {
    init {
        require(productId in BillingProducts.subscriptions)
        require(purchaseToken.isNotBlank())
        require(obfuscatedAccountId.matches(Regex("^[a-f0-9]{64}$")))
    }
}

/**
 * Google receives only a one-way account correlation, never a Clerk subject,
 * email address, or other directly identifying value.
 */
fun obfuscatedBillingAccountId(canonicalUserId: String): String {
    require(canonicalUserId.isNotBlank())
    return MessageDigest.getInstance("SHA-256")
        .digest("ctrlb-google-play:$canonicalUserId".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
