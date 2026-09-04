package ai.trimm.mobile.auth

import ai.trimm.mobile.network.supabase
import io.github.jan.supabase.auth.auth

object AuthTokenProvider {
    suspend fun currentToken(): String {
        return supabase.auth.currentSessionOrNull()?.accessToken ?: ""
    }
    
    suspend fun currentToken(forceRefresh: Boolean): String {
        return supabase.auth.currentSessionOrNull()?.accessToken ?: ""
    }
}
