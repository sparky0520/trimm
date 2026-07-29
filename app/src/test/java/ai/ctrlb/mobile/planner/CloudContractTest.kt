package ai.ctrlb.mobile.planner

import org.junit.Assert.assertTrue
import org.junit.Test

class CloudContractTest {
    @Test
    fun `mobile pins supported ctrlB cloud contract versions`() {
        val document = requireNotNull(javaClass.classLoader?.getResource("contracts/cloud-contract.lock.json")).readText()
        assertTrue(document.contains("\"source\": \"sparky0520/ctrlb-cloud\""))
        for (name in listOf("planner", "evidencePacket", "editOperations", "derivativeJobs", "mediaIndex", "billingCatalog", "accountLifecycle")) {
            assertTrue("Missing supported version for $name", document.contains("\"$name\": 1"))
        }
    }
}
