package io.agentscope.reme.diary

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.IOException
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject

/** Only an origin is accepted. Redirects are disabled so a bearer token cannot move hosts. */
object ServerAddress {
    fun isLocal(host: String): Boolean {
        val value = host.lowercase()
        if (
            value == "localhost" ||
                value.endsWith(".local") ||
                value.endsWith(".lan") ||
                value == "::1" ||
                value.startsWith("fc") && value.contains(':') ||
                value.startsWith("fd") && value.contains(':') ||
                value.startsWith("fe80:")
        )
            return true
        val parts = value.split('.').mapNotNull { it.toIntOrNull() }
        return parts.size == 4 &&
            (parts[0] in listOf(10, 127) ||
                parts.take(2) == listOf(192, 168) ||
                parts.take(2) == listOf(169, 254) ||
                parts[0] == 172 && parts[1] in 16..31)
    }

    fun parse(address: String, port: String): HttpUrl {
        val raw = address.trim()
        require(raw.startsWith("http://") || raw.startsWith("https://")) {
            "地址需要以 http:// 或 https:// 开头"
        }
        val url = raw.toHttpUrl()
        require(
            url.username.isEmpty() &&
                url.password.isEmpty() &&
                url.encodedPath == "/" &&
                url.query == null &&
                url.fragment == null
        ) {
            "地址只填写主机，不能包含账号、路径或查询参数"
        }
        val number = port.toIntOrNull() ?: error("请填写有效端口")
        require(number in 1..65535) { "端口需要在 1–65535 之间" }
        val authority = raw.substringAfter("://").substringBefore('/')
        val explicit =
            if (authority.startsWith("[")) authority.substringAfter(']', "").startsWith(":")
            else authority.contains(':')
        require(!explicit || url.port == number) { "地址与端口填写不一致" }
        return url.newBuilder().port(number).build()
    }
}

data class Connection(val origin: String, val token: String, val instance: String) {
    val scope: String
        get() = "$origin#$instance"
}

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("connection", Context.MODE_PRIVATE)
    val deviceId: String =
        prefs.getString("device", null)
            ?: java.util.UUID.randomUUID().toString().also {
                prefs.edit().putString("device", it).commit()
            }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return store.getKey("reme-token", null) as? SecretKey
            ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                .apply {
                    init(
                        KeyGenParameterSpec.Builder(
                                "reme-token",
                                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                            )
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .build()
                    )
                }
                .generateKey()
    }

    fun connection(): Connection? = runCatching {
        val origin = prefs.getString("origin", null) ?: return null
        val data = Base64.decode(prefs.getString("token", null) ?: return null, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
        Connection(
            origin,
            String(cipher.doFinal(data.copyOfRange(12, data.size)), Charsets.UTF_8),
            prefs.getString("instance", "")!!,
        )
    }
        .getOrNull()

    var autoRead: Boolean
        get() = prefs.getBoolean("autoRead", true)
        set(value) {
            prefs.edit().putBoolean("autoRead", value).apply()
        }

    var voice: String
        get() = prefs.getString("voice", "") ?: ""
        set(value) {
            prefs.edit().putString("voice", value).apply()
        }

    fun save(value: Connection) {
        val cipher =
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted =
            Base64.encodeToString(
                cipher.iv + cipher.doFinal(value.token.toByteArray(Charsets.UTF_8)),
                Base64.NO_WRAP,
            )
        check(
            prefs
                .edit()
                .putString("origin", value.origin)
                .putString("instance", value.instance)
                .putString("token", encrypted)
                .commit()
        )
    }
}

class ApiFailure(val code: Int, message: String) : IOException(message)

class DiaryApi(private val connection: Connection) {
    private val client =
        OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.MINUTES)
            .build()

    private fun request(job: String, body: JSONObject): Request {
        require(job.matches(Regex("android_[a-z]+")))
        val origin = connection.origin.toHttpUrl()
        val url = origin.newBuilder().addPathSegment(job).build()
        check(url.host == origin.host && url.port == origin.port && url.scheme == origin.scheme)
        return Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${connection.token}")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
    }

    suspend fun post(job: String, body: JSONObject): JSONObject =
        withContext(Dispatchers.IO) {
            val call = client.newCall(request(job, body))
            call.execute().use { response ->
                if (!response.isSuccessful)
                    throw ApiFailure(
                        response.code,
                        when (response.code) {
                            401 -> "Token 无效，请在连接设置中更新"
                            413 -> "录音超过服务端大小限制"
                            422 -> "输入格式不正确"
                            else -> "服务暂时不可用（${response.code}）"
                        },
                    )
                val json = JSONObject(response.body.string())
                if (!json.optBoolean("success")) {
                    val code =
                        json.optJSONObject("answer")?.optJSONObject("error")?.optString("code")
                    val answer = json.optString("answer")
                    throw ApiFailure(
                        when {
                            code == "revision_conflict" || answer.contains("Conflict") -> 409
                            code == "service_unavailable" -> 503
                            code == "access_denied" -> 403
                            else -> 422
                        },
                        if ((code == "revision_conflict" || answer.contains("Conflict")))
                            "内容已有更新，请刷新后重试"
                        else "服务未完成这次操作，请检查输入后重试",
                    )
                }
                json.optJSONObject("answer") ?: JSONObject().put("items", json.opt("answer"))
            }
        }

    fun events(id: String) =
        callbackFlow<JSONObject> {
            val call = client.newCall(request("android_events", JSONObject().put("run_id", id)))
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        close(e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        response.use {
                            if (!response.isSuccessful) {
                                close(ApiFailure(response.code, "进度连接中断"))
                                return
                            }
                            try {
                                val source = response.body.source()
                                while (!source.exhausted()) {
                                    val line = source.readUtf8Line() ?: break
                                    if (!line.startsWith("data:")) continue
                                    val data = line.removePrefix("data:").trim()
                                    if (data == "[DONE]") break
                                    trySend(JSONObject(data))
                                }
                                close()
                            } catch (error: IOException) {
                                close(error)
                            }
                        }
                    }
                }
            )
            awaitClose { call.cancel() }
        }
}
