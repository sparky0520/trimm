package ai.ctrlb.mobile.planner

import org.junit.Assert.assertTrue
import org.junit.Test

class CloudContractTest {
    @Test
    fun `mobile pins supported trimm cloud contract versions`() {
        val document = requireNotNull(javaClass.classLoader?.getResource("contracts/cloud-contract.lock.json")).readText()
        assertTrue(document.contains("\"source\": \"sparky0520/trimm-cloud\""))
        for (name in listOf("planner", "evidencePacket", "editOperations", "derivativeJobs", "mediaIndex", "agentRuns", "accountLifecycle")) {
            assertTrue("Missing supported version for $name", document.contains("\"$name\": 1"))
        }
    }
}
