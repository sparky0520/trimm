package ai.ctrlb.mobile.network

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import ai.ctrlb.mobile.BuildConfig

data class ChatEditRequest(
    val prompt: String,
    val parentId: String?,
    val chatId: String,
    val transcript: String?
)

data class ChatEditResponse(
    val status: String,
    val checkpointId: String
)

data class ChatTitleRequest(val transcript: String)
data class ChatTitleResponse(val title: String)

interface ChatApiService {
    @POST("/v1/chats/edit")
    suspend fun submitEdit(@Body request: ChatEditRequest): ChatEditResponse

    @POST("/v1/chats/title")
    suspend fun generateTitle(@Body request: ChatTitleRequest): ChatTitleResponse

    companion object {
        fun create(): ChatApiService {
            val retrofit = Retrofit.Builder()
                .baseUrl(BuildConfig.PLANNER_URL.removeSuffix("/v1") + "/")
                .addConverterFactory(GsonConverterFactory.create())
                .build()
            return retrofit.create(ChatApiService::class.java)
        }
    }
}
