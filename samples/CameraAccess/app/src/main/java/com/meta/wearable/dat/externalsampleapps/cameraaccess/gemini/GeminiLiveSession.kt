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

  /** Transcription of user's audio input. */
  data class InputTranscription(val text: String) : ServerEvent()

  /** Transcription of model's audio output. */
  data class OutputTranscription(val text: String) : ServerEvent()

  /** The model's response was interrupted (e.g. user started speaking). */
  data object Interrupted : ServerEvent()

  /** A pending tool call was cancelled (e.g. user interrupted during tool execution). */
  data class ToolCallCancellation(val ids: List<String>) : ServerEvent()

  /** An error occurred. */
  data class Error(val message: String) : ServerEvent()
}

class GeminiLiveSession(
    private val apiKey: String,
    private val systemInstruction: String =
        "You are an AI assistant for someone wearing Meta Ray-Ban smart glasses. " +
            "You can see through their camera and have a voice conversation. " +
            "Keep responses concise and natural.\n\n" +
            "You are smart and capable. Answer questions, have conversations, describe what you see, " +
            "identify objects, read text, and help the user directly whenever you can. " +
            "You have access to the live camera feed — use it to answer visual questions " +
            "(e.g. \"what is this plant?\", \"what does that sign say?\", \"describe what you see\"). " +
            "Handle these yourself without calling any tool.\n\n" +
            "You also have ONE tool: execute. This connects you to a powerful personal assistant " +
            "that can take real-world actions you cannot do yourself. " +
            "Only call execute when the user's request requires an EXTERNAL ACTION that you " +
            "genuinely cannot perform, such as:\n" +
            "- Sending a message to someone (WhatsApp, Telegram, iMessage, Slack, etc.)\n" +
            "- Adding to or modifying a shopping list, reminder, note, todo, or calendar event\n" +
            "- Searching the web for real-time information you don't know\n" +
            "- Controlling smart home devices or interacting with external apps/services\n" +
            "- Storing or remembering information persistently for later\n\n" +
            "Do NOT call execute for things you can handle directly:\n" +
            "- Answering questions about what you see through the camera\n" +
            "- General knowledge questions you already know the answer to\n" +
            "- Having a normal conversation\n" +
            "- Describing, identifying, or analyzing visual content\n\n" +
            "When you DO call execute, be detailed in the task description. " +
            "Include all relevant context: names, content, platforms, quantities, etc.\n\n" +
            "IMPORTANT: Before calling execute, ALWAYS speak a brief acknowledgment first. " +
            "For example:\n" +
            "- \"Sure, let me add that to your shopping list.\" then call execute.\n" +
            "- \"On it, sending that message.\" then call execute.\n" +
            "Never call execute silently — the user needs verbal confirmation that you heard them " +
            "and are working on it.",
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
    Log.d(TAG, "sendText: '$text', state=${_sessionState.value}")
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

  /** Send a JPEG image frame as real-time input (for vision context, ~1fps). */
  fun sendImage(jpegData: ByteArray) {
    val base64Image = Base64.encodeToString(jpegData, Base64.NO_WRAP)
    val message =
        JSONObject().apply {
          put(
              "realtimeInput",
              JSONObject().apply {
                put(
                    "mediaChunks",
                    JSONArray().put(
                        JSONObject().apply {
                          put("mimeType", "image/jpeg")
                          put("data", base64Image)
                        },
                    ),
                )
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
    // Log message type for debugging (first key indicates the message kind)
    val msgType = message.keys().asSequence().firstOrNull() ?: "unknown"
    Log.d(TAG, "Sending [$msgType] (${text.length} bytes)")
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
                          "thinkingConfig",
                          JSONObject().apply { put("thinkingBudget", 0) },
                      )
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
                                        "Your only way to take action. You have no memory, " +
                                            "storage, or ability to do anything on your own " +
                                            "-- use this tool for anything that requires an " +
                                            "external action: sending messages, searching the " +
                                            "web, adding to lists, setting reminders, creating " +
                                            "notes, research, drafts, scheduling, smart home " +
                                            "control, app interactions, buying things, or any " +
                                            "request that goes beyond answering a question.",
                                    )
                                    put(
                                        "parameters",
                                        JSONObject().apply {
                                          put("type", "object")
                                          put(
                                              "properties",
                                              JSONObject().apply {
                                                put(
                                                    "task",
                                                    JSONObject().apply {
                                                      put("type", "string")
                                                      put(
                                                          "description",
                                                          "Clear, detailed description of what " +
                                                              "to do. Include all relevant " +
                                                              "context: names, content, " +
                                                              "platforms, quantities, etc.",
                                                      )
                                                    },
                                                )
                                              },
                                          )
                                          put("required", JSONArray().put("task"))
                                        },
                                    )
                                    put("behavior", "BLOCKING")
                                  },
                              ),
                          )
                        },
                    ),
                )
                put(
                    "realtimeInputConfig",
                    JSONObject().apply {
                      put(
                          "automaticActivityDetection",
                          JSONObject().apply {
                            put("disabled", false)
                            put("startOfSpeechSensitivity", "START_SENSITIVITY_HIGH")
                            put("endOfSpeechSensitivity", "END_SENSITIVITY_LOW")
                            put("silenceDurationMs", 500)
                            put("prefixPaddingMs", 40)
                          },
                      )
                      put("activityHandling", "START_OF_ACTIVITY_INTERRUPTS")
                      put("turnCoverage", "TURN_INCLUDES_ALL_INPUT")
                    },
                )
                put("inputAudioTranscription", JSONObject())
                put("outputAudioTranscription", JSONObject())
              },
          )
        }

    Log.d(TAG, "Setup message: ${setupMessage.toString().take(500)}...")
    ws.send(setupMessage.toString())
  }

  private fun handleServerMessage(text: String) {
    try {
      val json = JSONObject(text)
      val keys = json.keys().asSequence().toList()
      Log.d(TAG, "Received server message keys=$keys (${text.length} bytes)")

      when {
        json.has("setupComplete") -> {
          Log.d(TAG, "Setup complete")
          _sessionState.value = SessionState.CONNECTED
          _serverEvents.tryEmit(ServerEvent.SetupComplete)
        }

        json.has("toolCall") -> {
          handleToolCall(json.getJSONObject("toolCall"))
        }

        json.has("toolCallCancellation") -> {
          val cancellation = json.getJSONObject("toolCallCancellation")
          val idsArray = cancellation.optJSONArray("ids")
          val ids = mutableListOf<String>()
          if (idsArray != null) {
            for (i in 0 until idsArray.length()) {
              ids.add(idsArray.getString(i))
            }
          }
          Log.d(TAG, "Tool call cancellation: $ids")
          _serverEvents.tryEmit(ServerEvent.ToolCallCancellation(ids))
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
    // Check for interruption
    val interrupted = serverContent.optBoolean("interrupted", false)
    if (interrupted) {
      Log.d(TAG, "serverContent: interrupted")
      _serverEvents.tryEmit(ServerEvent.Interrupted)
      return
    }

    // Handle input transcription (what the user said)
    val inputTranscription = serverContent.optJSONObject("inputTranscription")
    if (inputTranscription != null) {
      val text = inputTranscription.optString("text", "")
      if (text.isNotEmpty()) {
        Log.d(TAG, "inputTranscription: $text")
        _serverEvents.tryEmit(ServerEvent.InputTranscription(text))
      }
    }

    // Handle output transcription (what the model said)
    val outputTranscription = serverContent.optJSONObject("outputTranscription")
    if (outputTranscription != null) {
      val text = outputTranscription.optString("text", "")
      if (text.isNotEmpty()) {
        Log.d(TAG, "outputTranscription: $text")
        _serverEvents.tryEmit(ServerEvent.OutputTranscription(text))
      }
    }

    // Check for turn completion
    val turnComplete = serverContent.optBoolean("turnComplete", false)
    val hasModelTurn = serverContent.has("modelTurn")

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

