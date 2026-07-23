package ai.ctrlb.mobile.billing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BillingContractTest {
    @Test
    fun accountCorrelationIsStableOpaqueAndUserScoped() {
        val first = obfuscatedBillingAccountId("user-123")
        assertEquals(64, first.length)
        assertEquals(first, obfuscatedBillingAccountId("user-123"))
        assertNotEquals(first, obfuscatedBillingAccountId("user-456"))
    }

    @Test
    fun submissionsAcceptOnlyKnownProductsAndOpaqueAccounts() {
        PurchaseSubmission(BillingProducts.PRO_MONTHLY, "purchase-token", "a".repeat(64))
        assertThrows(IllegalArgumentException::class.java) {
            PurchaseSubmission("forged-plan", "purchase-token", "a".repeat(64))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PurchaseSubmission(BillingProducts.PRO_MONTHLY, "purchase-token", "clerk-user-id")
        }
    }
}
