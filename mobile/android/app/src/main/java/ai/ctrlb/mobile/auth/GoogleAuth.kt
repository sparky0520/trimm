package ai.ctrlb.mobile.auth

import android.app.Activity
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.builtin.IDToken
import ai.ctrlb.mobile.network.supabase
import ai.ctrlb.mobile.BuildConfig

suspend fun signInWithGoogle(activity: Activity): Boolean {
    val credentialManager = CredentialManager.create(activity)
    
    // We use a dummy client ID here if none is provided, but user needs to set it in strings or env
    val webClientId = BuildConfig.GOOGLE_WEB_CLIENT_ID
    
    val googleIdOption = GetGoogleIdOption.Builder()
        .setFilterByAuthorizedAccounts(false)
        .setServerClientId(webClientId)
        .build()

    val request = GetCredentialRequest.Builder()
        .addCredentialOption(googleIdOption)
        .build()

    return try {
        val credential = try {
            credentialManager.getCredential(activity, request).credential
        } catch (e: androidx.credentials.exceptions.NoCredentialException) {
            val signUpOption = com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption.Builder(webClientId).build()
            val signUpRequest = GetCredentialRequest.Builder().addCredentialOption(signUpOption).build()
            credentialManager.getCredential(activity, signUpRequest).credential
        }

        val idToken = when (credential) {
            is com.google.android.libraries.identity.googleid.GoogleIdTokenCredential -> credential.idToken
            is androidx.credentials.CustomCredential -> {
                if (credential.type == com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.createFrom(credential.data).idToken
                } else null
            }
            else -> null
        }

        if (idToken != null) {
            supabase.auth.signInWith(IDToken) {
                this.idToken = idToken
                this.provider = Google
            }
            true
        } else {
            Log.e("GoogleAuth", "Unexpected credential type")
            false
        }
    } catch (e: Exception) {
        Log.e("GoogleAuth", "Sign in failed", e)
        false
    }
}
