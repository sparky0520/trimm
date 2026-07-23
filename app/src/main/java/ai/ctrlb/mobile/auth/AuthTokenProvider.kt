package ai.ctrlb.mobile.auth

import ai.ctrlb.mobile.BuildConfig
import com.clerk.api.Clerk
import com.clerk.api.network.serialization.ClerkResult

/**
 * Obtains a short-lived credential at the instant a protected request begins.
 * Tokens are never persisted or passed through WorkManager input data.
 */
object AuthTokenProvider {
    suspend fun currentToken(): String {
        if (BuildConfig.CLERK_PUBLISHABLE_KEY.isBlank()) {
            check(BuildConfig.PLANNER_TOKEN.isNotBlank()) {
                "Sign in is required before using online editing services."
            }
            return BuildConfig.PLANNER_TOKEN
        }
        check(Clerk.activeUser != null) { "Sign in is required before using online editing services." }
        return when (val result = Clerk.auth.getToken()) {
            is ClerkResult.Success -> result.value
            is ClerkResult.Failure -> error("Your session is unavailable. Sign in again and retry.")
        }
    }
}
