package com.rayneo.autocapture

import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * 危险分析器 - 调用 OpenRouter VLM API 分析图像
 */
class DangerAnalyzer(private val apiKey: String) {

    companion object {
        private const val TAG = "DangerAnalyzer"
        private const val OPENROUTER_URL = "https://openrouter.ai/api/v1/chat/completions"
        private const val MODEL = "google/gemini-2.0-flash-001"
    }

    // 自定义 DNS 解析器，绕过系统 DNS 问题
    private val customDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            return try {
                // 首先尝试系统 DNS
                Dns.SYSTEM.lookup(hostname)
            } catch (e: Exception) {
                Log.w(TAG, "System DNS failed, trying hardcoded fallback for $hostname")
                // 如果系统 DNS 失败，使用硬编码的 IP 地址
                when (hostname) {
                    "openrouter.ai" -> listOf(
                        InetAddress.getByName("104.18.3.115"),
                        InetAddress.getByName("104.18.2.115")
                    )
                    else -> throw e
                }
            }
        }
    }

    private val client = OkHttpClient.Builder()
        .dns(customDns)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    /**
     * 分析结果
     */
    data class AnalysisResult(
        val isDanger: Boolean,
        val rawResponse: String? = null,
        val error: String? = null
    )

    /**
     * 分析图像是否存在危险
     * @param imageBytes JPEG 图像字节数组
     * @return 分析结果
     */
    suspend fun analyzeImage(imageBytes: ByteArray): AnalysisResult = withContext(Dispatchers.IO) {
        try {
            // 1. 图像转 Base64
            val base64Image = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
            Log.d(TAG, "Image encoded to Base64, size: ${base64Image.length}")

            // 2. 构建请求体
            val requestBody = buildRequestBody(base64Image)

            // 3. 构建请求
            val request = Request.Builder()
                .url(OPENROUTER_URL)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(requestBody.toRequestBody("application/json".toMediaType()))
                .build()

            // 4. 发送请求
            Log.d(TAG, "Sending request to OpenRouter...")
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()

            if (!response.isSuccessful) {
                Log.e(TAG, "API request failed: ${response.code} - $responseBody")
                return@withContext AnalysisResult(
                    isDanger = false,
                    error = "API error: ${response.code}"
                )
            }

            // 5. 解析响应
            Log.d(TAG, "Response received: $responseBody")
            val apiResponse = gson.fromJson(responseBody, OpenRouterResponse::class.java)
            val answer = apiResponse.choices?.firstOrNull()?.message?.content?.uppercase() ?: ""

            Log.i(TAG, "VLM response: $answer")

            // 6. 判断是否危险
            val isDanger = answer.contains("YES")
            return@withContext AnalysisResult(
                isDanger = isDanger,
                rawResponse = answer
            )

        } catch (e: Exception) {
            Log.e(TAG, "Analysis failed: ${e.javaClass.simpleName}: ${e.message}", e)
            return@withContext AnalysisResult(
                isDanger = false,
                error = e.message
            )
        }
    }

    private fun buildRequestBody(base64Image: String): String {
        val request = OpenRouterRequest(
            model = MODEL,
            messages = listOf(
                Message(
                    role = "user",
                    content = listOf(
                        ContentPart(
                            type = "text",
                            text = "You are a safety assistant. Look at this image from a first-person perspective. Is there any IMMEDIATE physical danger (e.g., approaching cars, deep holes, aggressive dogs, fire)? Answer with only 'YES' or 'NO'."
                        ),
                        ContentPart(
                            type = "image_url",
                            imageUrl = ImageUrl(url = "data:image/jpeg;base64,$base64Image")
                        )
                    )
                )
            ),
            maxTokens = 10
        )
        return gson.toJson(request)
    }

    // OpenRouter API 数据类
    data class OpenRouterRequest(
        val model: String,
        val messages: List<Message>,
        @SerializedName("max_tokens") val maxTokens: Int
    )

    data class Message(
        val role: String,
        val content: List<ContentPart>
    )

    data class ContentPart(
        val type: String,
        val text: String? = null,
        @SerializedName("image_url") val imageUrl: ImageUrl? = null
    )

    data class ImageUrl(val url: String)

    data class OpenRouterResponse(
        val choices: List<Choice>?
    )

    data class Choice(
        val message: ResponseMessage?
    )

    data class ResponseMessage(
        val content: String?
    )
}
