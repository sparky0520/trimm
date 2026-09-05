package ai.trimm.mobile.network

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import ai.trimm.mobile.BuildConfig

import com.google.gson.annotations.SerializedName

data class ChatCompletionResponse(
    @SerializedName("type") val type: String,
    @SerializedName("operations") val operations: List<com.google.gson.JsonObject>,
    @SerializedName("summary") val summary: String? = null
)

data class ChatTitleRequest(
    @SerializedName("transcript") val transcript: String
)

data class ChatTitleResponse(
    @SerializedName("title") val title: String
)

interface ChatApiService {
    @POST("/v1/chat/completion")
    suspend fun chatCompletion(
        @Header("Authorization") authorization: String,
        @Body request: ai.trimm.mobile.planner.AgentRunRequest
    ): ChatCompletionResponse

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
