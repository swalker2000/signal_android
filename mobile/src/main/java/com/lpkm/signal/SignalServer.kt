package com.lpkm.signal

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit

data class RemoteScreen(
    val id: Long,
    val name: String,
    val version: Int,
    val html: String = "",
)

data class AuthResult(
    val token: String,
    val expiresInSeconds: Long,
)

class ServerException(
    val kind: Kind,
    val serverMessage: String? = null,
    val unauthorized: Boolean = false,
) : Exception(serverMessage ?: kind.name) {
    enum class Kind {
        BAD_URL,
        NEED_CREDENTIALS,
        UNREACHABLE,
        BAD_RESPONSE,
        HTTP,
    }
}

object ServerUrls {
    const val DEFAULT = "http://89.22.169.3:7070/"

    fun normalize(raw: String): String {
        val trimmed = raw.trim().ifBlank { DEFAULT }
        val withScheme = when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            else -> "http://$trimmed"
        }
        val uri = try {
            URI(withScheme)
        } catch (_: Exception) {
            throw IllegalArgumentException("bad url")
        }
        if (uri.host.isNullOrBlank() || (uri.scheme != "http" && uri.scheme != "https")) {
            throw IllegalArgumentException("bad url")
        }
        return withScheme.trimEnd('/')
    }
}

object DeviceApi {
    fun authBody(login: String, password: String): String {
        return JSONObject()
            .put("login", login)
            .put("password", password)
            .toString()
    }

    fun parseAuth(json: String): AuthResult {
        val obj = JSONObject(json)
        val token = obj.optString("token")
        if (token.isBlank()) throw IllegalArgumentException("token")
        return AuthResult(token, obj.optLong("expiresIn"))
    }

    fun parseBriefs(json: String): List<RemoteScreen> {
        val array = JSONArray(json)
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(
                    RemoteScreen(
                        id = item.getLong("id"),
                        name = item.optString("name").ifBlank { "Экран ${item.getLong("id")}" },
                        version = item.optInt("version", 1),
                    ),
                )
            }
        }
    }

    fun parseDisplay(json: String): RemoteScreen {
        val obj = JSONObject(json)
        val html = obj.optString("html")
        if (html.isBlank()) throw IllegalArgumentException("html")
        return RemoteScreen(
            id = obj.getLong("id"),
            name = obj.optString("name").ifBlank { "Экран ${obj.getLong("id")}" },
            version = obj.optInt("version", 1),
            html = html,
        )
    }

    fun parseError(json: String): String? {
        return try {
            JSONObject(json).optString("error").ifBlank { null }
        } catch (_: Exception) {
            null
        }
    }
}

class SignalServer {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()

    fun authenticate(rawUrl: String, login: String, password: String): AuthResult {
        val base = baseUrl(rawUrl)
        if (login.isBlank() || password.isBlank()) {
            throw ServerException(ServerException.Kind.NEED_CREDENTIALS)
        }
        val json = exchange(
            url = "$base/api/v1/auth",
            method = "POST",
            token = null,
            body = DeviceApi.authBody(login.trim(), password),
        )
        return try {
            DeviceApi.parseAuth(json)
        } catch (_: Exception) {
            throw ServerException(ServerException.Kind.BAD_RESPONSE)
        }
    }

    fun listDisplays(baseUrl: String, token: String): List<RemoteScreen> {
        val json = exchange("$baseUrl/api/v1/displays", "GET", token, null)
        return try {
            DeviceApi.parseBriefs(json)
        } catch (_: Exception) {
            throw ServerException(ServerException.Kind.BAD_RESPONSE)
        }
    }

    fun downloadDisplay(baseUrl: String, token: String, id: Long): RemoteScreen {
        val json = exchange("$baseUrl/api/v1/displays/$id", "GET", token, null)
        return try {
            DeviceApi.parseDisplay(json)
        } catch (_: Exception) {
            throw ServerException(ServerException.Kind.BAD_RESPONSE)
        }
    }

    private fun baseUrl(rawUrl: String): String {
        return try {
            ServerUrls.normalize(rawUrl)
        } catch (_: IllegalArgumentException) {
            throw ServerException(ServerException.Kind.BAD_URL)
        }
    }

    private fun exchange(url: String, method: String, token: String?, body: String?): String {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("Cache-Control", "no-store")
            .apply {
                if (!token.isNullOrBlank()) header("Authorization", "Bearer $token")
                if (body != null) {
                    method(method, body.toRequestBody(JSON))
                } else {
                    method(method, null)
                }
            }
            .build()
        try {
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (text.length > 2_500_000) throw ServerException(ServerException.Kind.BAD_RESPONSE)
                if (!response.isSuccessful) {
                    throw ServerException(
                        kind = ServerException.Kind.HTTP,
                        serverMessage = DeviceApi.parseError(text),
                        unauthorized = response.code == 401,
                    )
                }
                return text
            }
        } catch (error: ServerException) {
            throw error
        } catch (_: IOException) {
            throw ServerException(ServerException.Kind.UNREACHABLE)
        }
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
