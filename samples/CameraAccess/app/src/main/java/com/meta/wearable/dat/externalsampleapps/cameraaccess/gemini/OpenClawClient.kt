/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// OpenClawClient - HTTP Client for OpenClaw Gateway
//
// Sends task execution requests to the OpenClaw gateway via HTTP POST.
// OpenClaw is an external service with 56+ connected skills that can
// fulfill a wide variety of user requests (shopping lists, reminders,
// smart home control, etc.).

package com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class OpenClawClient(private val baseUrl: String) {

  companion object {
    private const val TAG = "OpenClawClient"
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
  }

  private val client =
      OkHttpClient.Builder()
          .connectTimeout(30, TimeUnit.SECONDS)
          .readTimeout(60, TimeUnit.SECONDS)
          .writeTimeout(30, TimeUnit.SECONDS)
          .build()

  /**
   * Execute a task via the OpenClaw gateway.
   *
   * @param task The natural language task description to execute.
   * @return A [Result] containing the response body on success or an exception on failure.
   */
  suspend fun executeTask(task: String): Result<String> =
      withContext(Dispatchers.IO) {
        try {
          val requestBody =
              JSONObject().apply { put("task", task) }.toString().toRequestBody(JSON_MEDIA_TYPE)

          val request =
              Request.Builder()
                  .url(baseUrl)
                  .post(requestBody)
                  .header("Content-Type", "application/json")
                  .build()

          Log.d(TAG, "Sending task to OpenClaw: $task")

          client.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: ""
            if (response.isSuccessful) {
              Log.d(TAG, "OpenClaw response: $body")
              Result.success(body)
            } else {
              val errorMsg = "OpenClaw request failed: ${response.code} $body"
              Log.e(TAG, errorMsg)
              Result.failure(Exception(errorMsg))
            }
          }
        } catch (e: Exception) {
          Log.e(TAG, "OpenClaw request error: ${e.message}", e)
          Result.failure(e)
        }
      }
}

