package ai.trimm.mobile.auth

import ai.trimm.mobile.network.supabase
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus

object AuthTokenProvider {
    /**
     * Returns the current Supabase access token with no race condition.
     *
     * [supabase.auth.sessionStatus] is a StateFlow — its [value] is always the
     * authoritative, synchronously-readable current state. [SessionStatus.Authenticated]
     * carries the [UserSession] directly on the sealed class instance, so reading the
     * token and checking the auth state is a single atomic snapshot with no separate
     * lookup that could lag behind (as [currentSessionOrNull] can right after sign-in).
     */
    suspend fun currentToken(): String = getToken(forceRefresh = false)

    /**
     * Same as [currentToken] but forces a Supabase session refresh before returning.
     * Use this after a 401 to ensure the token is genuinely fresh.
     */
    suspend fun currentToken(forceRefresh: Boolean): String = getToken(forceRefresh)

    private suspend fun getToken(forceRefresh: Boolean): String {
        val status = supabase.auth.sessionStatus.value

        // Not authenticated at all — return empty and let the caller handle it.
        if (status !is SessionStatus.Authenticated) return ""

        val session = status.session

        val secondsUntilExpiry = session.expiresAt.epochSeconds - (System.currentTimeMillis() / 1000)
        if (forceRefresh || secondsUntilExpiry < 60) {
            // Refresh the session in-place. After this the StateFlow value updates,
            // so we read it fresh to return the new access token.
            runCatching { supabase.auth.refreshCurrentSession() }
            val refreshed = supabase.auth.sessionStatus.value
            return if (refreshed is SessionStatus.Authenticated) refreshed.session.accessToken else ""
        }

        return session.accessToken
    }
}

