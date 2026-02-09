/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// OpenClawClient - HTTP Client for OpenClaw Gateway
//
// Sends task execution requests to the OpenClaw gateway via HTTP POST
// to the /v1/chat/completions endpoint (OpenAI-compatible).
// Uses Bearer token authentication and session key for continuity.
// Follows the same protocol as VisionClaw's OpenClawBridge.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class OpenClawClient(
    private val baseUrl: String,
    private val gatewayToken: String,
) {

  companion object {
    private const val TAG = "OpenClawClient"
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
  }

  private val client =
      OkHttpClient.Builder()
          .connectTimeout(10, TimeUnit.SECONDS)
          .readTimeout(45, TimeUnit.SECONDS)
          .writeTimeout(10, TimeUnit.SECONDS)
          .build()

  // Session key for continuity across multiple tool calls
  private var sessionKey: String = newSessionKey()

  /** Reset the session key (e.g. on reconnect). */
  fun resetSession() {
    sessionKey = newSessionKey()
    Log.d(TAG, "New session: $sessionKey")
  }

  /**
   * Execute a task via the OpenClaw gateway using the /v1/chat/completions endpoint.
   *
   * Follows the OpenAI-compatible chat completions protocol:
   * - POST to {baseUrl}/v1/chat/completions
   * - Bearer token authentication
   * - Session continuity via x-openclaw-session-key header
   * - Request body: {"model": "openclaw", "messages": [...], "stream": false}
   * - Response: choices[0].message.content
   *
   * @param task The natural language task description to execute.
   * @param toolName The name of the tool being executed (for logging).
   * @return A [Result] containing the response content on success or an exception on failure.
   */
  suspend fun executeTask(task: String, toolName: String = "execute"): Result<String> =
      withContext(Dispatchers.IO) {
        try {
          // Build URL: baseUrl + /v1/chat/completions
          val url = "${baseUrl.trimEnd('/')}/v1/chat/completions"

          // Build OpenAI-compatible request body
          val requestBody =
              JSONObject()
                  .apply {
                    put("model", "openclaw")
                    put(
                        "messages",
                        JSONArray()
                            .put(
                                JSONObject().apply {
                                  put("role", "user")
                                  put("content", task)
                                },
                            ),
                    )
                    put("stream", false)
                  }
                  .toString()
                  .toRequestBody(JSON_MEDIA_TYPE)

          val request =
              Request.Builder()
                  .url(url)
                  .post(requestBody)
                  .header("Content-Type", "application/json")
                  .header("Authorization", "Bearer $gatewayToken")
                  .header("x-openclaw-session-key", sessionKey)
                  .build()

          Log.d(TAG, "[$toolName] Sending task to OpenClaw: $task")
          Log.d(TAG, "[$toolName] URL: $url, session: $sessionKey")

          client.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: ""
            if (response.isSuccessful) {
              // Parse OpenAI-compatible response: choices[0].message.content
              val content = parseCompletionContent(body)
              Log.d(TAG, "[$toolName] OpenClaw result: ${content.take(200)}")
              Result.success(content)
            } else {
              val errorMsg = "HTTP ${response.code} - ${body.take(200)}"
              Log.e(TAG, "[$toolName] Chat failed: $errorMsg")
              Result.failure(Exception("Agent returned HTTP ${response.code}"))
            }
          }
        } catch (e: Exception) {
          Log.e(TAG, "[$toolName] Agent error: ${e.message}", e)
          Result.failure(Exception("Agent error: ${e.message}"))
        }
      }

  /**
   * Parse the content from an OpenAI-compatible chat completion response.
   * Expected format: {"choices": [{"message": {"content": "..."}}]}
   * Falls back to the raw response body if parsing fails.
   */
  private fun parseCompletionContent(responseBody: String): String {
    try {
      val json = JSONObject(responseBody)
      val choices = json.optJSONArray("choices")
      if (choices != null && choices.length() > 0) {
        val firstChoice = choices.getJSONObject(0)
        val message = firstChoice.optJSONObject("message")
        if (message != null) {
          val content = message.optString("content", "")
          if (content.isNotEmpty()) return content
        }
      }
    } catch (e: Exception) {
      Log.w(TAG, "Failed to parse chat completion response, using raw body: ${e.message}")
    }
    // Fallback: return the raw response
    return responseBody
  }

  /** Generate a new session key with timestamp for debugging/traceability. */
  private fun newSessionKey(): String {
    val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
    formatter.timeZone = TimeZone.getTimeZone("UTC")
    val ts = formatter.format(Date())
    return "agent:main:glass:$ts"
  }
}
