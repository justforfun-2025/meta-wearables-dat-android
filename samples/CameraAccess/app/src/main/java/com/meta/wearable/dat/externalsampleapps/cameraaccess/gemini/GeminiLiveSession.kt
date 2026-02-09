/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// GeminiLiveSession - Gemini Live API WebSocket Client
//
// Manages a WebSocket connection to the Gemini Live API (BidiGenerateContent).
// Handles session setup with tool declarations, message parsing, and the
// bidirectional protocol for audio streaming and function calling.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini

import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** State of the Gemini Live WebSocket session. */
enum class SessionState {
  DISCONNECTED,
  CONNECTING,
  CONNECTED,
  ERROR,
}

/** Events emitted by the server over the WebSocket. */
sealed class ServerEvent {
  /** Session setup completed successfully. */
  data object SetupComplete : ServerEvent()

  /** Audio data from the model's spoken response (raw PCM bytes). */
  data class Audio(val pcmData: ByteArray) : ServerEvent()

  /** Text content from the model (used for transcript display). */
  data class Text(val text: String) : ServerEvent()

  /** The model's turn is complete. */
  data object TurnComplete : ServerEvent()

  /** The model is requesting a tool call. */
  data class ToolCall(
      val functionCallId: String,
      val functionName: String,
      val args: Map<String, String>,
  ) : ServerEvent()

  /** An error occurred. */
  data class Error(val message: String) : ServerEvent()
}

class GeminiLiveSession(
    private val apiKey: String,
    private val systemInstruction: String =
        "You are a helpful voice assistant running on smart glasses. " +
            "You can fulfill user requests by calling the execute tool. " +
            "Always verbally acknowledge the user's request before calling the tool. " +
            "After receiving the tool result, confirm completion to the user.",
) {
  companion object {
    private const val TAG = "GeminiLiveSession"
    private const val BASE_URL =
        "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
    private const val MODEL = "models/gemini-2.5-flash-native-audio-preview-12-2025"
    private const val VOICE_NAME = "Puck"
  }

  private val client =
      OkHttpClient.Builder()
          .readTimeout(0, TimeUnit.MILLISECONDS) // No timeout for WebSocket
          .pingInterval(30, TimeUnit.SECONDS)
          .build()

  private var webSocket: WebSocket? = null

  private val _sessionState = MutableStateFlow(SessionState.DISCONNECTED)
  val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

  private val _serverEvents = MutableSharedFlow<ServerEvent>(extraBufferCapacity = 64)
  val serverEvents: SharedFlow<ServerEvent> = _serverEvents.asSharedFlow()

  /** Connect to the Gemini Live API and send the setup message. */
  fun connect() {
    if (_sessionState.value == SessionState.CONNECTING ||
        _sessionState.value == SessionState.CONNECTED
    ) {
      Log.w(TAG, "Already connected or connecting")
      return
    }

    _sessionState.value = SessionState.CONNECTING

    val url = "$BASE_URL?key=$apiKey"
    val request = Request.Builder().url(url).build()

    webSocket =
        client.newWebSocket(
            request,
            object : WebSocketListener() {
              override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket opened, sending setup message")
                sendSetupMessage(webSocket)
              }

              override fun onMessage(webSocket: WebSocket, text: String) {
                handleServerMessage(text)
              }

              override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handleServerMessage(bytes.utf8())
              }

              override fun onFailure(
                  webSocket: WebSocket,
                  t: Throwable,
                  response: Response?,
              ) {
                Log.e(TAG, "WebSocket failure: ${t.message}", t)
                _sessionState.value = SessionState.ERROR
                _serverEvents.tryEmit(
                    ServerEvent.Error(t.message ?: "WebSocket connection failed")
                )
              }

              override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: $code $reason")
                webSocket.close(1000, null)
              }

              override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $code $reason")
                _sessionState.value = SessionState.DISCONNECTED
              }
            },
        )
  }

  /** Disconnect from the Gemini Live API. */
  fun disconnect() {
    webSocket?.close(1000, "Client disconnecting")
    webSocket = null
    _sessionState.value = SessionState.DISCONNECTED
  }

  /** Send a text message as user input. */
  fun sendText(text: String) {
    val message =
        JSONObject().apply {
          put(
              "clientContent",
              JSONObject().apply {
                put(
                    "turns",
                    JSONArray().put(
                        JSONObject().apply {
                          put("role", "user")
                          put(
                              "parts",
                              JSONArray().put(JSONObject().apply { put("text", text) }),
                          )
                        },
                    ),
                )
                put("turnComplete", true)
              },
          )
        }
    send(message)
  }

  /** Send a chunk of audio data as real-time input. [pcmData] should be 16kHz 16-bit mono PCM. */
  fun sendAudio(pcmData: ByteArray) {
    val base64Audio = Base64.encodeToString(pcmData, Base64.NO_WRAP)
    val message =
        JSONObject().apply {
          put(
              "realtimeInput",
              JSONObject().apply {
                put(
                    "mediaChunks",
                    JSONArray().put(
                        JSONObject().apply {
                          put("mimeType", "audio/pcm;rate=16000")
                          put("data", base64Audio)
                        },
                    ),
                )
              },
          )
        }
    send(message)
  }

  /** Send a tool response back to Gemini after processing a tool call. */
  fun sendToolResponse(functionCallId: String, result: JSONObject) {
    val message =
        JSONObject().apply {
          put(
              "toolResponse",
              JSONObject().apply {
                put(
                    "functionResponses",
                    JSONArray().put(
                        JSONObject().apply {
                          put("id", functionCallId)
                          put("response", result)
                        },
                    ),
                )
              },
          )
        }
    send(message)
  }

  private fun send(message: JSONObject) {
    val text = message.toString()
    val sent = webSocket?.send(text) ?: false
    if (!sent) {
      Log.w(TAG, "Failed to send message (WebSocket not connected)")
    }
  }

  private fun sendSetupMessage(ws: WebSocket) {
    val setupMessage =
        JSONObject().apply {
          put(
              "setup",
              JSONObject().apply {
                put("model", MODEL)
                put(
                    "generationConfig",
                    JSONObject().apply {
                      put("responseModalities", JSONArray().put("AUDIO"))
                      put(
                          "speechConfig",
                          JSONObject().apply {
                            put(
                                "voiceConfig",
                                JSONObject().apply {
                                  put(
                                      "prebuiltVoiceConfig",
                                      JSONObject().apply { put("voiceName", VOICE_NAME) },
                                  )
                                },
                            )
                          },
                      )
                    },
                )
                put(
                    "systemInstruction",
                    JSONObject().apply {
                      put(
                          "parts",
                          JSONArray().put(
                              JSONObject().apply { put("text", systemInstruction) },
                          ),
                      )
                    },
                )
                put(
                    "tools",
                    JSONArray().put(
                        JSONObject().apply {
                          put(
                              "functionDeclarations",
                              JSONArray().put(
                                  JSONObject().apply {
                                    put("name", "execute")
                                    put(
                                        "description",
                                        "Fulfill a user request by routing it to the " +
                                            "OpenClaw gateway. Use this for any actionable " +
                                            "task the user asks you to perform.",
                                    )
                                    put(
                                        "parameters",
                                        JSONObject().apply {
                                          put("type", "OBJECT")
                                          put(
                                              "properties",
                                              JSONObject().apply {
                                                put(
                                                    "task",
                                                    JSONObject().apply {
                                                      put("type", "STRING")
                                                      put(
                                                          "description",
                                                          "A natural language description " +
                                                              "of the task to execute",
                                                      )
                                                    },
                                                )
                                              },
                                          )
                                          put("required", JSONArray().put("task"))
                                        },
                                    )
                                  },
                              ),
                          )
                        },
                    ),
                )
              },
          )
        }

    ws.send(setupMessage.toString())
  }

  private fun handleServerMessage(text: String) {
    try {
      val json = JSONObject(text)

      when {
        json.has("setupComplete") -> {
          Log.d(TAG, "Setup complete")
          _sessionState.value = SessionState.CONNECTED
          _serverEvents.tryEmit(ServerEvent.SetupComplete)
        }

        json.has("toolCall") -> {
          handleToolCall(json.getJSONObject("toolCall"))
        }

        json.has("serverContent") -> {
          handleServerContent(json.getJSONObject("serverContent"))
        }

        else -> {
          Log.d(TAG, "Unhandled server message: ${text.take(200)}")
        }
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error parsing server message: ${e.message}", e)
      _serverEvents.tryEmit(ServerEvent.Error("Failed to parse server message: ${e.message}"))
    }
  }

  private fun handleToolCall(toolCall: JSONObject) {
    val functionCalls = toolCall.getJSONArray("functionCalls")
    for (i in 0 until functionCalls.length()) {
      val call = functionCalls.getJSONObject(i)
      val id = call.getString("id")
      val name = call.getString("name")
      val argsJson = call.optJSONObject("args") ?: JSONObject()

      val args = mutableMapOf<String, String>()
      argsJson.keys().forEach { key -> args[key] = argsJson.optString(key, "") }

      Log.d(TAG, "Tool call received: $name(${args}), id=$id")
      _serverEvents.tryEmit(ServerEvent.ToolCall(id, name, args))
    }
  }

  private fun handleServerContent(serverContent: JSONObject) {
    // Check for turn completion
    val turnComplete = serverContent.optBoolean("turnComplete", false)

    // Process model turn content
    val modelTurn = serverContent.optJSONObject("modelTurn")
    if (modelTurn != null) {
      val parts = modelTurn.optJSONArray("parts")
      if (parts != null) {
        for (i in 0 until parts.length()) {
          val part = parts.getJSONObject(i)

          // Handle inline audio data
          val inlineData = part.optJSONObject("inlineData")
          if (inlineData != null) {
            val mimeType = inlineData.optString("mimeType", "")
            val data = inlineData.optString("data", "")
            if (mimeType.startsWith("audio/") && data.isNotEmpty()) {
              val pcmBytes = Base64.decode(data, Base64.DEFAULT)
              _serverEvents.tryEmit(ServerEvent.Audio(pcmBytes))
            }
          }

          // Handle text content
          val textContent = part.optString("text", "")
          if (textContent.isNotEmpty()) {
            _serverEvents.tryEmit(ServerEvent.Text(textContent))
          }
        }
      }
    }

    if (turnComplete) {
      _serverEvents.tryEmit(ServerEvent.TurnComplete)
    }
  }
}

