package ai.trimm.mobile.auth

import ai.trimm.mobile.network.supabase
import io.github.jan.supabase.auth.auth

object AuthTokenProvider {
    suspend fun currentToken(): String {
        return supabase.auth.currentAccessTokenOrNull() ?: ""
    }
    
    suspend fun currentToken(forceRefresh: Boolean): String {
        // We could implement refresh logic here if needed, but for now just return the current token
        return supabase.auth.currentAccessTokenOrNull() ?: ""
    }
}
