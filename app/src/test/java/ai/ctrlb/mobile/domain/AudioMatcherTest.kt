package ai.ctrlb.mobile.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioMatcherTest {
    @Test fun `ranking is deterministic and excludes rights-unclear assets`() {
        val good = AudioCatalogItem(
            "warm", AudioKind.MUSIC, "music/warm.mp3", 60_000, 90f, .35f, setOf("warm"),
            "license-1", "a".repeat(64), allowedUse = setOf("rendered-video"), status = "ready"
        )
        val unclear = good.copy(id = "unclear", licenseId = "")
        assertEquals(listOf("warm"), AudioMatcher.rank(listOf(unclear, good), 30_000, VisualStyle.WARM).map { it.item.id })
    }

    @Test fun `quarantined revoked and attribution-incomplete assets never rank`() {
        val good = AudioCatalogItem(
            "ready", AudioKind.MUSIC, "music/ready.mp3", 60_000, 90f, .35f, setOf("warm"),
            "license-1", "b".repeat(64), attribution = "Artist — Track",
            attributionRequired = true, allowedUse = setOf("rendered-video"), status = "ready"
        )
        val unsafe = listOf(
            good.copy(id = "quarantined", status = "quarantined", quarantineReasons = setOf("license-changed")),
            good.copy(id = "revoked", quarantineReasons = setOf("provider-deleted")),
            good.copy(id = "missing-attribution", attribution = null),
            good.copy(id = "wrong-scope", allowedUse = setOf("personal")),
            good.copy(id = "bad-checksum", checksum = "invalid")
        )
        assertEquals(listOf("ready"), AudioMatcher.rank(unsafe + good, 30_000, VisualStyle.WARM).map { it.item.id })
    }
}
