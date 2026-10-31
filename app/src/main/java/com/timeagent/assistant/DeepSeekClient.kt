package com.timeagent.assistant

import com.google.gson.JsonParser
import kotlinx.coroutines.*
import java.io.IOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

fun interface AssistantTransport {
    suspend fun post(apiKey: String, payload: String): String
}

sealed interface CloudResult {
    data class Success(val interpretation: Interpretation) : CloudResult
    data class Failure(val message: String) : CloudResult
}

class DeepSeekClient(private val transport: AssistantTransport = OfficialDeepSeekTransport()) {
    suspend fun interpret(input: String, context: AssistantContext, apiKey: String, model: String, dialogue: List<ConversationTurn> = emptyList()): CloudResult {
        if (apiKey.isBlank()) return CloudResult.Failure("尚未配置 DeepSeek API Key。请先在本页设置中填写，或使用本地解析。")
        if (apiKey.length > 512 || apiKey.any { it == '\r' || it == '\n' } || !DeepSeekProtocol.validModel(model)) return CloudResult.Failure("API Key 或模型名格式无效，请检查设置。")
        return try {
            val payload = DeepSeekProtocol.request(input, context, model, dialogue)
            val response = transport.post(apiKey, payload)
            currentCoroutineContext().ensureActive()
            require(response.length <= 262144)
            val root = JsonParser.parseString(response).asJsonObject
            val choices = root.getAsJsonArray("choices")
            require(choices.size() == 1)
            val first = choices[0].asJsonObject
            require(first["finish_reason"]?.asString == "stop")
            val message = first.getAsJsonObject("message")
            require(!message.has("tool_calls") || message.getAsJsonArray("tool_calls").size() == 0)
            val content = message["content"]
            require(content != null && content.isJsonPrimitive && content.asJsonPrimitive.isString && content.asString.isNotBlank())
            CloudResult.Success(CloudCommandCodec.decode(content.asString))
        } catch (e: CancellationException) { throw e }
        catch (e: DeepSeekHttpFailure) { CloudResult.Failure(when (e.status) {
            401, 403 -> "DeepSeek 鉴权失败，请检查应用内 API Key。"
            402 -> "DeepSeek 账户额度不足，请检查账户。"
            429 -> "DeepSeek 请求过于频繁，请稍后重试。"
            else -> "DeepSeek 服务暂时不可用（HTTP ${e.status}），输入仍保留。"
        }) }
        catch (_: IOException) { CloudResult.Failure("DeepSeek 网络连接失败或超时，输入仍保留，可重试或改用本地解析。") }
        catch (_: Exception) { CloudResult.Failure("DeepSeek 返回内容为空、截断或格式异常，未执行任何操作；输入仍保留。") }
    }
}

private class DeepSeekHttpFailure(val status: Int) : IOException()

/** No redirects, configurable host, SDK, logging, or disk cache: the key goes only to the official HTTPS host. */
class OfficialDeepSeekTransport(private val connectionFactory: () -> HttpsURLConnection = { URL(DeepSeekProtocol.ENDPOINT).openConnection() as HttpsURLConnection }) : AssistantTransport {
    override suspend fun post(apiKey: String, payload: String): String = withContext(Dispatchers.IO) {
        val connection = connectionFactory()
        val requestContext = currentCoroutineContext()
        suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { runCatching { connection.disconnect() } }
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 20000
            connection.readTimeout = 45000
            connection.useCaches = false
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            val bytes = payload.toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            requestContext.ensureActive()
            val code = connection.responseCode
            if (code != 200) throw DeepSeekHttpFailure(code)
            val result = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val out = StringBuilder()
                val buffer = CharArray(4096)
                while (true) {
                    requestContext.ensureActive()
                    val count = reader.read(buffer)
                    if (count < 0) break
                    require(out.length + count <= 262144)
                    out.append(buffer, 0, count)
                }
                out.toString()
            }
            if (continuation.isActive) continuation.resume(result)
        } catch (e: Exception) {
            if (continuation.isActive) continuation.resumeWithException(e)
        } finally { connection.disconnect() }
        }
    }
}
