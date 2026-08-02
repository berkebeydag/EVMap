package com.berke.ioniqscope.auth

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.berke.ioniqscope.BuildConfig
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential

/** Who signed in, as Google reported them. */
data class GoogleUser(
    val name: String?,
    val email: String?,
    val photoUrl: String?
)

/** What came back from asking, including the ways it can legitimately not work. */
sealed interface SignInResult {
    data class Success(val user: GoogleUser) : SignInResult
    /** The user closed the sheet. Not an error, and must not be reported as one. */
    data object Cancelled : SignInResult
    /** No Google account on the phone, or none the user was willing to use. */
    data object NoAccount : SignInResult
    /** The build has no client ID, so there is nothing to ask. */
    data object NotConfigured : SignInResult
    data class Failed(val message: String) : SignInResult
}

/**
 * Sign in with Google, and nothing else.
 *
 * There is no server behind this app, so signing in buys exactly one thing: a real
 * name and picture instead of a blank profile. It is deliberately not wired to
 * anything else — no trip is uploaded, no favourite leaves the phone, and signing out
 * takes nothing with it, because there is nowhere for any of it to have gone.
 *
 * Credential Manager rather than the old GoogleSignIn API, which is deprecated.
 * [GetSignInWithGoogleOption] is the explicit button flow: it shows every Google
 * account on the device and treats a first-time sign-in the same as a returning one,
 * where the filter-by-authorized-accounts flow would show nothing at all to a user who
 * has never signed in — which is every user of this app.
 */
class GoogleAccount(private val context: Context) {

    val isConfigured: Boolean get() = BuildConfig.GOOGLE_CLIENT_ID.isNotBlank()

    suspend fun signIn(activityContext: Context): SignInResult {
        if (!isConfigured) return SignInResult.NotConfigured

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(
                GetSignInWithGoogleOption.Builder(BuildConfig.GOOGLE_CLIENT_ID).build()
            )
            .build()

        return try {
            val response = CredentialManager.create(context)
                .getCredential(activityContext, request)
            val credential = response.credential
            if (credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                val id = GoogleIdTokenCredential.createFrom(credential.data)
                SignInResult.Success(
                    GoogleUser(
                        name = id.displayName ?: id.givenName,
                        email = id.id,
                        photoUrl = id.profilePictureUri?.toString()
                    )
                )
            } else {
                SignInResult.Failed("Beklenmeyen kimlik türü döndü.")
            }
        } catch (e: GetCredentialCancellationException) {
            SignInResult.Cancelled
        } catch (e: NoCredentialException) {
            SignInResult.NoAccount
        } catch (e: GetCredentialException) {
            SignInResult.Failed(e.message ?: "Giriş yapılamadı.")
        }
    }

    /**
     * Forgets the account on this phone.
     *
     * Only clears the credential state Credential Manager holds; the stored name and
     * picture are cleared by the caller. There is no session to end anywhere else.
     */
    suspend fun signOut() {
        runCatching {
            CredentialManager.create(context)
                .clearCredentialState(androidx.credentials.ClearCredentialStateRequest())
        }
    }
}
