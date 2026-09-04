package ai.ctrlb.mobile.network

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import ai.ctrlb.mobile.BuildConfig

data class ChatEditRequest(
    val prompt: String,
    val parentId: String?,
    val chatId: String,
    val transcript: String?,
    val videoUrl: String? = null
)

data class ChatEditResponse(
    val status: String,
    val checkpointId: String
)

data class ChatTitleRequest(val transcript: String)
data class ChatTitleResponse(val title: String)

interface ChatApiService {
    @POST("/v1/chats/edit")
    suspend fun submitEdit(
        @Header("Authorization") authorization: String,
        @Body request: ChatEditRequest
    ): ChatEditResponse

    @POST("/v1/chats/title")
    suspend fun generateTitle(
        @Header("Authorization") authorization: String,
        @Body request: ChatTitleRequest
    ): ChatTitleResponse

    @POST("/v1/projects/sync")
    suspend fun syncProject(
        @Header("Authorization") authorization: String,
        @Body projectJson: okhttp3.RequestBody
    ): okhttp3.ResponseBody

    @retrofit2.http.GET("/v1/projects")
    suspend fun getProjects(
        @Header("Authorization") authorization: String
    ): com.google.gson.JsonObject

    @retrofit2.http.GET("/v1/chats/{chatId}/checkpoints")
    suspend fun getCheckpoints(
        @Header("Authorization") authorization: String,
        @retrofit2.http.Path("chatId") chatId: String
    ): com.google.gson.JsonObject

    @retrofit2.http.GET("/v1/projects/{id}")
    suspend fun getProject(@Header("Authorization") authorization: String, @retrofit2.http.Path("id") id: String): com.google.gson.JsonObject

    @retrofit2.http.DELETE("/v1/projects/{id}")
    suspend fun deleteProject(@Header("Authorization") authorization: String, @retrofit2.http.Path("id") id: String): okhttp3.ResponseBody

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
