package app.archivefetcher.data

import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

sealed interface LoginResult {
    data class Success(val cookies: Map<String, String>) : LoginResult
    data class Failure(val reason: String) : LoginResult
}

object LoginParser {
    private val json = Json { ignoreUnknownKeys = true }

    /** Reads the answer of archive.org's login service: session cookies on success, a reason code otherwise. */
    fun parse(body: String): LoginResult {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return LoginResult.Failure("unexpected response")
        val values = root["values"] as? JsonObject
        if (root["success"]?.jsonPrimitive?.booleanOrNull != true) {
            val reason = values?.get("reason")?.jsonPrimitive?.contentOrNull
                ?: root["error"]?.jsonPrimitive?.contentOrNull
                ?: "unknown"
            return LoginResult.Failure(reason)
        }
        val cookies = (values?.get("cookies") as? JsonObject)
            ?.mapNotNull { (name, value) -> value.jsonPrimitive.contentOrNull?.let { name to it } }
            ?.toMap()
            .orEmpty()
        return if (cookies.isEmpty()) LoginResult.Failure("no session cookies in the response") else LoginResult.Success(cookies)
    }
}

/** Logs in with an archive.org account. The password is only sent to archive.org and is never stored. */
class ArchiveLogin(private val client: OkHttpClient) {
    suspend fun login(email: String, password: String): LoginResult =
        try {
            val form = FormBody.Builder().add("email", email).add("password", password).build()
            val request = Request.Builder().url(LOGIN_URL).post(form).build()
            client.newCall(request).awaitResponse().use { response ->
                LoginParser.parse(withContext(Dispatchers.IO) { response.body?.string().orEmpty() })
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            LoginResult.Failure(e.message ?: "network error")
        }

    private companion object {
        const val LOGIN_URL = "https://archive.org/services/xauthn/?op=login"
    }
}
