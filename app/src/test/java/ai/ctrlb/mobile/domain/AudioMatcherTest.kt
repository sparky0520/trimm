package ai.ctrlb.mobile.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioMatcherTest {
    @Test fun `ranking is deterministic and excludes rights-unclear assets`() {
        val good = AudioCatalogItem("warm", AudioKind.MUSIC, "music/warm.mp3", 60_000, 90f, .35f, setOf("warm"), "license-1", "sha")
        val unclear = good.copy(id = "unclear", licenseId = "")
        assertEquals(listOf("warm"), AudioMatcher.rank(listOf(unclear, good), 30_000, VisualStyle.WARM).map { it.item.id })
    }
}
