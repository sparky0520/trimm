package ai.trimm.mobile.network

import retrofit2.http.Body
import retrofit2.http.POST

data class BgmRequest(
    val prompt: String,
    val durationSecs: Float,
    val beats: List<Float> = emptyList(),
    val isEffect: Boolean = false
)

data class BgmResponse(
    val url: String,
    val prompt: String,
    val duration: Float
)

data class SfxRequest(
    val prompt: String
)

data class SfxResponse(
    val url: String,
    val source: String
)

interface AudioService {
    @POST("/v1/audio/generate-bgm")
    suspend fun generateBgm(@Body request: BgmRequest): BgmResponse

    @POST("/v1/audio/fetch-sfx")
    suspend fun fetchSfx(@Body request: SfxRequest): SfxResponse
}
