/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// GeminiLiveViewModel - Gemini Live Session Controller
//
// Manages the full lifecycle of a Gemini Live voice assistant session:
// - WebSocket session connection and teardown
// - Microphone audio capture (16 kHz PCM) streamed to Gemini via realtimeInput
// - Speaker audio playback (24 kHz PCM) from Gemini's spoken responses
// - Tool call routing: intercepts execute() calls, dispatches to OpenClaw HTTP
//   gateway, and returns results via toolResponse
// - Conversation transcript management for UI display

package com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini

import android.annotation.SuppressLint
import android.app.Application
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meta.wearable.dat.externalsampleapps.cameraaccess.BuildConfig
import java.io.ByteArrayOutputStream
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

class GeminiLiveViewModel(application: Application) : AndroidViewModel(application) {

  companion object {
    private const val TAG = "GeminiLiveViewModel"

    // Audio capture settings (input to Gemini)
    private const val CAPTURE_SAMPLE_RATE = 16000
    private const val CAPTURE_CHANNEL = AudioFormat.CHANNEL_IN_MONO
    private const val CAPTURE_ENCODING = AudioFormat.ENCODING_PCM_16BIT

    // Audio playback settings (output from Gemini)
    private const val PLAYBACK_SAMPLE_RATE = 24000
    private const val PLAYBACK_CHANNEL = AudioFormat.CHANNEL_OUT_MONO
    private const val PLAYBACK_ENCODING = AudioFormat.ENCODING_PCM_16BIT

    // How often to send audio chunks (in milliseconds)
    private const val AUDIO_CHUNK_DURATION_MS = 100

    // How often to send video frames to Gemini (~1fps)
    private const val VIDEO_FRAME_INTERVAL_MS = 1000L

    // JPEG quality for video frames sent to Gemini (0-100)
    private const val VIDEO_FRAME_JPEG_QUALITY = 50
  }

  private val session = GeminiLiveSession(apiKey = BuildConfig.GEMINI_API_KEY)

  private val openClawClient =
      if (BuildConfig.OPENCLAW_URL.isNotEmpty()) {
        OpenClawClient(
            baseUrl = BuildConfig.OPENCLAW_URL,
            gatewayToken = BuildConfig.OPENCLAW_TOKEN,
        )
      } else {
        null
      }

  private val _uiState = MutableStateFlow(GeminiLiveUiState())
  val uiState: StateFlow<GeminiLiveUiState> = _uiState.asStateFlow()

  private var audioRecord: AudioRecord? = null
  private var audioTrack: AudioTrack? = null
  private var recordingJob: Job? = null
  private var eventCollectionJob: Job? = null
  private var sessionStateJob: Job? = null
  private var playbackJob: Job? = null
  private val audioPlaybackChannel = Channel<ByteArray>(capacity = Channel.UNLIMITED)

  // Buffer to accumulate assistant text responses across chunks
  private val assistantTextBuffer = StringBuilder()

  // Throttle video frame sending to ~1fps
  private var lastFrameSentMs = 0L

  /** Connect to the Gemini Live API. */
  fun connect() {
    if (_uiState.value.connectionState == SessionState.CONNECTING ||
        _uiState.value.connectionState == SessionState.CONNECTED
    ) {
      return
    }

    addTranscriptEntry(TranscriptRole.SYSTEM, "Connecting to Gemini Live...")
    openClawClient?.resetSession()
    initAudioTrack()
    startEventCollection()
    startSessionStateMonitoring()
    session.connect()
  }

  /** Disconnect from the Gemini Live API. */
  fun disconnect() {
    stopRecording()
    stopAudioPlayback()
    eventCollectionJob?.cancel()
    eventCollectionJob = null
    sessionStateJob?.cancel()
    sessionStateJob = null
    session.disconnect()
    _uiState.update {
      it.copy(
          connectionState = SessionState.DISCONNECTED,
          isRecording = false,
          activeToolCall = null,
      )
    }
    addTranscriptEntry(TranscriptRole.SYSTEM, "Disconnected")
  }

  /** Toggle microphone recording on/off. */
  fun toggleRecording() {
    if (_uiState.value.isRecording) {
      stopRecording()
    } else {
      startRecording()
    }
  }

  /** Send a text message to Gemini (fallback for when audio is not available). */
  fun sendTextMessage(text: String) {
    if (text.isBlank()) return
    if (_uiState.value.connectionState != SessionState.CONNECTED) {
      Log.w(TAG, "Cannot send text: not connected")
      return
    }
    addTranscriptEntry(TranscriptRole.USER, text)
    session.sendText(text)
  }

  /**
   * Forward a video frame from the DAT camera stream to Gemini for visual context.
   * Frames are throttled to ~1fps and compressed to JPEG before sending.
   */
  fun onVideoFrame(bitmap: Bitmap) {
    if (_uiState.value.connectionState != SessionState.CONNECTED) return
    val now = System.currentTimeMillis()
    if (now - lastFrameSentMs < VIDEO_FRAME_INTERVAL_MS) return
    lastFrameSentMs = now

    viewModelScope.launch(Dispatchers.IO) {
      try {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, VIDEO_FRAME_JPEG_QUALITY, stream)
        session.sendImage(stream.toByteArray())
      } catch (e: Exception) {
        Log.e(TAG, "Failed to send video frame: ${e.message}")
      }
    }
  }

  /** Clear any error message shown in the UI. */
  fun clearError() {
    _uiState.update { it.copy(errorMessage = null) }
  }

  // --- Audio Capture ---

  @SuppressLint("MissingPermission")
  private fun startRecording() {
    if (_uiState.value.connectionState != SessionState.CONNECTED) {
      Log.w(TAG, "Cannot start recording: not connected")
      return
    }

    val bufferSize =
        AudioRecord.getMinBufferSize(CAPTURE_SAMPLE_RATE, CAPTURE_CHANNEL, CAPTURE_ENCODING)
    if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
      Log.e(TAG, "Invalid audio buffer size: $bufferSize")
      _uiState.update { it.copy(errorMessage = "Audio recording not supported on this device") }
      return
    }

    try {
      audioRecord =
          AudioRecord(
              MediaRecorder.AudioSource.MIC,
              CAPTURE_SAMPLE_RATE,
              CAPTURE_CHANNEL,
              CAPTURE_ENCODING,
              bufferSize,
          )

      if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
        Log.e(TAG, "AudioRecord failed to initialize")
        audioRecord?.release()
        audioRecord = null
        _uiState.update { it.copy(errorMessage = "Failed to initialize microphone") }
        return
      }

      audioRecord?.startRecording()
      _uiState.update { it.copy(isRecording = true) }

      // Calculate chunk size: samples per chunk duration
      val bytesPerSample = 2 // 16-bit = 2 bytes
      val chunkSize = (CAPTURE_SAMPLE_RATE * AUDIO_CHUNK_DURATION_MS / 1000) * bytesPerSample

      recordingJob =
          viewModelScope.launch(Dispatchers.IO) {
            val buffer = ByteArray(chunkSize)
            while (isActive && audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
              val bytesRead = audioRecord?.read(buffer, 0, chunkSize) ?: -1
              if (bytesRead > 0) {
                val chunk =
                    if (bytesRead == chunkSize) buffer else buffer.copyOfRange(0, bytesRead)
                session.sendAudio(chunk)
              }
            }
          }

      Log.d(TAG, "Audio recording started")
    } catch (e: Exception) {
      Log.e(TAG, "Failed to start recording: ${e.message}", e)
      _uiState.update { it.copy(errorMessage = "Microphone error: ${e.message}") }
    }
  }

  private fun stopRecording() {
    recordingJob?.cancel()
    recordingJob = null
    try {
      audioRecord?.stop()
    } catch (e: IllegalStateException) {
      Log.w(TAG, "AudioRecord stop error: ${e.message}")
    }
    audioRecord?.release()
    audioRecord = null
    _uiState.update { it.copy(isRecording = false) }
    Log.d(TAG, "Audio recording stopped")
  }

  // --- Audio Playback ---

  private fun initAudioTrack() {
    val bufferSize =
        AudioTrack.getMinBufferSize(PLAYBACK_SAMPLE_RATE, PLAYBACK_CHANNEL, PLAYBACK_ENCODING)

    audioTrack =
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(PLAYBACK_SAMPLE_RATE)
                    .setChannelMask(PLAYBACK_CHANNEL)
                    .setEncoding(PLAYBACK_ENCODING)
                    .build(),
            )
            .setBufferSizeInBytes(bufferSize * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

    audioTrack?.play()
    Log.d(TAG, "AudioTrack initialized and playing")

    // Single dedicated coroutine drains audio chunks sequentially —
    // avoids concurrent AudioTrack.write() calls that crash the native layer.
    playbackJob =
        viewModelScope.launch(Dispatchers.IO) {
          for (pcmData in audioPlaybackChannel) {
            try {
              audioTrack?.write(pcmData, 0, pcmData.size)
            } catch (e: Exception) {
              Log.e(TAG, "Audio playback error: ${e.message}", e)
            }
          }
        }
  }

  private fun playAudio(pcmData: ByteArray) {
    audioPlaybackChannel.trySend(pcmData)
  }

  private fun stopAudioPlayback() {
    playbackJob?.cancel()
    playbackJob = null
    try {
      audioTrack?.stop()
    } catch (e: IllegalStateException) {
      Log.w(TAG, "AudioTrack stop error: ${e.message}")
    }
    audioTrack?.release()
    audioTrack = null
  }

  // --- Event Handling ---

  private fun startSessionStateMonitoring() {
    sessionStateJob =
        viewModelScope.launch {
          session.sessionState.collect { state ->
            _uiState.update {
              it.copy(
                  connectionState = state,
                  // Clear spinning tool call if connection drops
                  activeToolCall =
                      if (state == SessionState.ERROR || state == SessionState.DISCONNECTED) null
                      else it.activeToolCall,
              )
            }
            if (state == SessionState.ERROR || state == SessionState.DISCONNECTED) {
              finalizeLastTranscriptEntry()
            }
          }
        }
  }

  private fun startEventCollection() {
    eventCollectionJob =
        viewModelScope.launch {
          session.serverEvents.collect { event ->
            when (event) {
              is ServerEvent.SetupComplete -> {
                addTranscriptEntry(TranscriptRole.SYSTEM, "Connected to Gemini Live")
              }

              is ServerEvent.Audio -> {
                playAudio(event.pcmData)
              }

              is ServerEvent.Text -> {
                // Accumulate text fragments; they'll be flushed on TurnComplete
                assistantTextBuffer.append(event.text)
              }

              is ServerEvent.InputTranscription -> {
                // Transcription of user's speech — show as a user message
                addOrUpdateTranscriptEntry(TranscriptRole.USER, event.text)
              }

              is ServerEvent.OutputTranscription -> {
                // Transcription of model's speech — show as an assistant message
                addOrUpdateTranscriptEntry(TranscriptRole.ASSISTANT, event.text)
              }

              is ServerEvent.TurnComplete -> {
                // Flush any accumulated assistant text (from text parts, not transcription)
                if (assistantTextBuffer.isNotEmpty()) {
                  // Only add if we didn't already get output transcription for this turn
                  assistantTextBuffer.clear()
                }
                // Finalize any in-progress transcript entries
                finalizeLastTranscriptEntry()
              }

              is ServerEvent.ToolCall -> {
                handleToolCall(event)
              }

              is ServerEvent.Interrupted -> {
                // User interrupted the model — flush any buffered text and stop playback
                assistantTextBuffer.clear()
                finalizeLastTranscriptEntry()
                // Drain pending audio to stop playback quickly
                while (audioPlaybackChannel.tryReceive().isSuccess) { /* discard */ }
                Log.d(TAG, "Model response interrupted")
              }

              is ServerEvent.ToolCallCancellation -> {
                Log.d(TAG, "Tool call cancelled: ${event.ids}")
                _uiState.update { it.copy(activeToolCall = null) }
                addTranscriptEntry(TranscriptRole.SYSTEM, "Tool call cancelled")
              }

              is ServerEvent.Error -> {
                _uiState.update { it.copy(errorMessage = event.message) }
                addTranscriptEntry(TranscriptRole.SYSTEM, "Error: ${event.message}")
              }
            }
          }
        }
  }

  // --- Tool Call Routing ---

  private fun handleToolCall(toolCall: ServerEvent.ToolCall) {
    val task = toolCall.args["task"] ?: "Unknown task"

    Log.d(TAG, "Processing tool call: ${toolCall.functionName}, task=$task")

    _uiState.update {
      it.copy(
          activeToolCall =
              ActiveToolCall(
                  functionCallId = toolCall.functionCallId,
                  functionName = toolCall.functionName,
                  task = task,
                  status = ToolCallStatus.EXECUTING,
              ),
      )
    }

    addTranscriptEntry(TranscriptRole.TOOL, "Executing: $task")

    viewModelScope.launch {
      val resultJson = JSONObject()

      if (openClawClient != null) {
        val result = openClawClient.executeTask(task, toolCall.functionName)
        result
            .onSuccess { content ->
              resultJson.put("result", content)
              _uiState.update {
                it.copy(activeToolCall = it.activeToolCall?.copy(status = ToolCallStatus.COMPLETED))
              }
              addTranscriptEntry(TranscriptRole.TOOL, "Completed: $task")
            }
            .onFailure { error ->
              resultJson.put("error", error.message ?: "Unknown error")
              _uiState.update {
                it.copy(activeToolCall = it.activeToolCall?.copy(status = ToolCallStatus.FAILED))
              }
              addTranscriptEntry(TranscriptRole.TOOL, "Failed: ${error.message}")
            }
      } else {
        // No OpenClaw URL configured — return a simulated success
        resultJson.put(
            "result",
            "Task acknowledged (OpenClaw not configured): $task",
        )
        _uiState.update {
          it.copy(activeToolCall = it.activeToolCall?.copy(status = ToolCallStatus.COMPLETED))
        }
        addTranscriptEntry(TranscriptRole.TOOL, "Completed (simulated): $task")
      }

      // Send the tool response back to Gemini
      session.sendToolResponse(
          toolCall.functionCallId,
          resultJson,
      )

      // Clear active tool call after a short delay for UI visibility
      _uiState.update { it.copy(activeToolCall = null) }
    }
  }

  // --- Transcript ---

  private fun addTranscriptEntry(role: TranscriptRole, text: String) {
    _uiState.update { currentState ->
      val updatedTranscript = (currentState.transcript + TranscriptEntry(role, text))
          .toImmutableList()
      currentState.copy(transcript = updatedTranscript)
    }
  }

  /**
   * Add a new transcript entry or append to the last one if it's the same role and not finalized.
   * Used for live transcription which arrives as incremental fragments.
   */
  private fun addOrUpdateTranscriptEntry(role: TranscriptRole, text: String) {
    _uiState.update { currentState ->
      val entries = currentState.transcript.toMutableList()
      val lastEntry = entries.lastOrNull()

      if (lastEntry != null && lastEntry.role == role && !lastEntry.isFinalized) {
        // Append to existing in-progress entry
        entries[entries.lastIndex] = lastEntry.copy(text = lastEntry.text + text)
      } else {
        // Add new in-progress entry
        entries.add(TranscriptEntry(role = role, text = text, isFinalized = false))
      }

      currentState.copy(transcript = entries.toImmutableList())
    }
  }

  /** Mark the last transcript entry as finalized (no more updates). */
  private fun finalizeLastTranscriptEntry() {
    _uiState.update { currentState ->
      val entries = currentState.transcript.toMutableList()
      val lastEntry = entries.lastOrNull()
      if (lastEntry != null && !lastEntry.isFinalized) {
        entries[entries.lastIndex] = lastEntry.copy(isFinalized = true)
      }
      currentState.copy(transcript = entries.toImmutableList())
    }
  }

  override fun onCleared() {
    super.onCleared()
    disconnect()
  }
}

