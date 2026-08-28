package com.replymint.auth

import android.app.Activity
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.replymint.BuildConfig
import com.replymint.data.ModeStore
import com.replymint.net.AuthClient

/**
 * Google Sign-In via Credential Manager, then a one-time exchange of the Google ID token for
 * our own long-lived session token at POST /v1/auth/google (verified server-side; the app never
 * trusts the ID token by itself).
 */
class SignInManager(private val activity: Activity) {

    private val store = ModeStore(activity)
    private val authClient = AuthClient(BuildConfig.BASE_URL)

    sealed interface Outcome {
        data class SignedIn(val email: String, val name: String?) : Outcome
        /** User dismissed the account picker — not an error, no message needed. */
        data object Cancelled : Outcome
        data class Failed(val message: String) : Outcome
    }

    suspend fun signIn(): Outcome {
        if (BuildConfig.GOOGLE_WEB_CLIENT_ID.isEmpty()) {
            return Outcome.Failed("Sign-in isn't configured in this build")
        }

        val idToken = try {
            // First pass only offers accounts that already authorized us (quiet for returning
            // users); NoCredentialException means a first-timer, so re-ask with the full picker.
            try {
                requestGoogleIdToken(filterByAuthorized = true)
            } catch (e: NoCredentialException) {
                requestGoogleIdToken(filterByAuthorized = false)
            }
        } catch (e: GetCredentialCancellationException) {
            return Outcome.Cancelled
        } catch (e: Exception) {
            return Outcome.Failed("Google sign-in failed: ${e.message ?: e.javaClass.simpleName}")
        } ?: return Outcome.Failed("Google returned an unexpected credential")

        return authClient.exchangeGoogleIdToken(idToken).fold(
            onSuccess = { session ->
                store.token = session.token
                store.email = session.user.email
                store.displayName = session.user.name
                Outcome.SignedIn(session.user.email, session.user.name)
            },
            onFailure = { Outcome.Failed("Couldn't reach ReplyMint: ${it.message}") }
        )
    }

    /** Best-effort server revocation, then local sign-out (always succeeds locally). */
    suspend fun signOut() {
        store.token?.let { authClient.signOut(it) }
        store.clearAuth()
    }

    private suspend fun requestGoogleIdToken(filterByAuthorized: Boolean): String? {
        val option = GetGoogleIdOption.Builder()
            .setServerClientId(BuildConfig.GOOGLE_WEB_CLIENT_ID)
            .setFilterByAuthorizedAccounts(filterByAuthorized)
            .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val credential = CredentialManager.create(activity)
            .getCredential(activity, request).credential
        return if (
            credential is CustomCredential &&
            credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            GoogleIdTokenCredential.createFrom(credential.data).idToken
        } else null
    }
}
