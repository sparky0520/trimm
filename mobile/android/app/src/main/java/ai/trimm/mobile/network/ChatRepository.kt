package ai.trimm.mobile.network

import ai.trimm.mobile.domain.AppliedEditCheckpoint
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive


class ChatRepository {
    
    suspend fun listenForCheckpoints(chatId: String): Flow<JsonObject> {
        val channel = supabase.realtime.channel("public:checkpoints:chat_id=eq.$chatId")
        val changes = channel.postgresChangeFlow<PostgresAction.Update>(schema = "public") {
            table = "checkpoints"
        }
        
        channel.subscribe()
        
        return changes
            .filter { it.record["chat_id"]?.jsonPrimitive?.content == chatId }
            .filter { it.record["status"]?.jsonPrimitive?.content in listOf("completed", "failed") }
            .mapNotNull { it.record }
    }
}
