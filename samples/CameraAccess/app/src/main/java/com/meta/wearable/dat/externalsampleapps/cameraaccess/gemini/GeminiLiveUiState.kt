/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// GeminiLiveUiState - Gemini Live Session UI State
//
// Manages the UI state for the Gemini Live voice assistant screen, including
// connection status, conversation transcript, recording state, and active
// tool call information.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/** A single entry in the conversation transcript. */
data class TranscriptEntry(
    val role: TranscriptRole,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
)

enum class TranscriptRole {
  USER,
  ASSISTANT,
  TOOL,
  SYSTEM,
}

/** Information about an active tool call being processed. */
data class ActiveToolCall(
    val functionCallId: String,
    val functionName: String,
    val task: String,
    val status: ToolCallStatus = ToolCallStatus.EXECUTING,
)

enum class ToolCallStatus {
  EXECUTING,
  COMPLETED,
  FAILED,
}

data class GeminiLiveUiState(
    val connectionState: SessionState = SessionState.DISCONNECTED,
    val transcript: ImmutableList<TranscriptEntry> = persistentListOf(),
    val isRecording: Boolean = false,
    val activeToolCall: ActiveToolCall? = null,
    val errorMessage: String? = null,
)

