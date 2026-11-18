package cc.skysparkle.sparkledns

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.util.Log
import androidx.annotation.StringRes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom

// Sign-in with a Skysparkle account: the app gets its access key without copying it by hand.
//
// OAuth for native apps (RFC 8252) with PKCE (RFC 7636): the account page opens in the browser,
// so the password never passes through the app. After "Connect" the site sends the browser to
// https://app.skysparkle.cc/app/callback?code=…&state=…, a verified App Link that only this app
// (signed with our key) receives, and the app trades the one-time code for a new key. Only this
// app knows the verifier, so even a code that leaked somewhere else is useless.
object SkysparkleSignIn {
    private const val CONNECT_URL = "https://skysparkle.cc/app/connect"
    private const val TOKEN_URL = "https://skysparkle.cc/api/app/token"
    private const val CALLBACK_HOST = "app.skysparkle.cc"
    private const val CALLBACK_PATH = "/app/callback"

    // The browser step must be finished within this time (the site keeps codes for 5 minutes)
    private const val PENDING_TTL_MS = 30 * 60_000L

    sealed interface State {
        data object Idle : State
        data object Waiting : State
        data object Exchanging : State
        data class Done(val key: String) : State
        data class Failed(@StringRes val message: Int) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    // Not tied to a screen: rotating or leaving the screen must not cancel the exchange
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun isCallback(uri: Uri?): Boolean =
        uri?.scheme == "https" && uri.host == CALLBACK_HOST && uri.path == CALLBACK_PATH

    // Opens the account page. Verifier and state are kept on disk: the system may stop the app
    // while the browser is in front.
    fun start(context: Context) {
        val verifier = randomToken(32)
        val state = randomToken(24)
        pending(context).edit()
            .putString(P_VERIFIER, verifier)
            .putString(P_STATE, state)
            .putLong(P_AT, System.currentTimeMillis())
            .apply()
        val url = Uri.parse(CONNECT_URL).buildUpon()
            .appendQueryParameter("challenge", challengeOf(verifier))
            .appendQueryParameter("state", state)
            .appendQueryParameter("device", Build.MODEL.take(40))
            .build()
        _state.value = State.Waiting
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: RuntimeException) {
            Log.w("SparkleDNS", "Cannot open the sign-in page", e)
            _state.value = State.Failed(R.string.no_browser)
        }
    }

    // The browser came back to the app: check state, then trade the code for a key.
    fun handle(context: Context, uri: Uri) {
        val app = context.applicationContext
        val p = pending(app)
        val state = p.getString(P_STATE, null)
        val verifier = p.getString(P_VERIFIER, null)
        val fresh = System.currentTimeMillis() - p.getLong(P_AT, 0) < PENDING_TTL_MS
        if (state == null || verifier == null || !fresh || uri.getQueryParameter("state") != state) {
            // A link that this app did not start, an old one, or a second tap on "Open SparkleDNS"
            // while the first code is still being exchanged: ignore it, keep any progress shown
            cancelWaiting()
            return
        }
        p.edit().clear().apply()
        if (uri.getQueryParameter("error") != null) {
            _state.value = State.Idle // "Cancel" on the site
            return
        }
        val code = uri.getQueryParameter("code")
        if (code.isNullOrEmpty()) {
            _state.value = State.Failed(R.string.sign_in_failed)
            return
        }
        _state.value = State.Exchanging
        scope.launch {
            _state.value = when (val r = exchange(code, verifier)) {
                is Result.Key -> {
                    Prefs(app).apply {
                        skysparkleKey = r.key
                        providerId = Providers.DEFAULT_ID
                    }
                    State.Done(r.key)
                }
                is Result.Error -> State.Failed(r.message)
            }
        }
    }

    // The screen showed the result; the next sign-in starts clean
    fun consume() {
        val s = _state.value
        if (s is State.Done || s is State.Failed) _state.value = State.Idle
    }

    // Returning from the browser without finishing (back button) should not leave a spinner
    fun cancelWaiting() {
        if (_state.value == State.Waiting) _state.value = State.Idle
    }

    private sealed interface Result {
        class Key(val key: String) : Result
        class Error(@StringRes val message: Int) : Result
    }

    private fun exchange(code: String, verifier: String): Result {
        val body = JSONObject().put("code", code).put("verifier", verifier).toString()
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(TOKEN_URL).post(body).build()
        return try {
            Http.client.newCall(request).execute().use { response ->
                val json = runCatching { JSONObject(response.body.string()) }.getOrNull()
                val key = json?.optString("key").orEmpty()
                when {
                    response.isSuccessful && SkysparkleKey.parse(key) != null -> Result.Key(key)
                    json?.optString("error") == "verify_email_first" -> Result.Error(R.string.sign_in_verify)
                    json?.optString("error") == "invalid_grant" -> Result.Error(R.string.sign_in_expired)
                    json?.optString("error") == "account_suspended" -> Result.Error(R.string.sign_in_suspended)
                    else -> Result.Error(R.string.sign_in_failed)
                }
            }
        } catch (e: IOException) {
            Log.w("SparkleDNS", "Key exchange failed", e)
            Result.Error(R.string.sign_in_network)
        } catch (e: RuntimeException) {
            // Runs in a scope without an exception handler: anything that escaped would crash the app
            Log.w("SparkleDNS", "Key exchange failed", e)
            Result.Error(R.string.sign_in_failed)
        }
    }

    private fun pending(context: Context) =
        context.applicationContext.getSharedPreferences("sign_in", Context.MODE_PRIVATE)

    private val random = SecureRandom()

    private fun randomToken(bytes: Int): String =
        b64url(ByteArray(bytes).also(random::nextBytes))

    private fun challengeOf(verifier: String): String =
        b64url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    private fun b64url(data: ByteArray): String =
        Base64.encodeToString(data, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    private const val P_VERIFIER = "verifier"
    private const val P_STATE = "state"
    private const val P_AT = "at"
}
