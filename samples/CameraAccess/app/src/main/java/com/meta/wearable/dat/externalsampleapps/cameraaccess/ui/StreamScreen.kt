/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// StreamScreen - Unified DAT Camera Streaming + Gemini Live AI UI
//
// This composable demonstrates the main streaming UI for DAT camera functionality with an
// integrated Gemini Live AI overlay. Video from wearable devices is displayed full-screen,
// and frames are simultaneously streamed to Gemini at ~1fps for visual context. The AI
// overlay provides voice + text interaction, a conversation transcript, and tool call status.
//
// Inspired by the VisionClaw iOS implementation (https://github.com/sseanliu/VisionClaw).

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meta.wearable.dat.camera.types.StreamSessionState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.R
import com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini.GeminiLiveViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini.SessionState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini.ToolCallStatus
import com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini.TranscriptEntry
import com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini.TranscriptRole
import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.StreamViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables.WearablesViewModel

@Composable
fun StreamScreen(
    wearablesViewModel: WearablesViewModel,
    modifier: Modifier = Modifier,
    streamViewModel: StreamViewModel =
        viewModel(
            factory =
                StreamViewModel.Factory(
                    application = (LocalActivity.current as ComponentActivity).application,
                    wearablesViewModel = wearablesViewModel,
                ),
        ),
    geminiViewModel: GeminiLiveViewModel = viewModel(),
) {
  val streamUiState by streamViewModel.uiState.collectAsStateWithLifecycle()
  val geminiUiState by geminiViewModel.uiState.collectAsStateWithLifecycle()
  val isGeminiActive = geminiUiState.connectionState != SessionState.DISCONNECTED

  LaunchedEffect(Unit) { streamViewModel.startStream() }

  // Forward video frames to Gemini at ~1fps when connected
  LaunchedEffect(streamUiState.videoFrame, geminiUiState.connectionState) {
    streamUiState.videoFrame?.let { frame ->
      geminiViewModel.onVideoFrame(frame)
    }
  }

  Box(modifier = modifier.fillMaxSize()) {
    // Layer 1: Full-screen video feed
    streamUiState.videoFrame?.let { videoFrame ->
      Image(
          bitmap = videoFrame.asImageBitmap(),
          contentDescription = stringResource(R.string.live_stream),
          modifier = Modifier.fillMaxSize(),
          contentScale = ContentScale.Crop,
      )
    }

    if (streamUiState.streamSessionState == StreamSessionState.STARTING) {
      CircularProgressIndicator(
          modifier = Modifier.align(Alignment.Center),
      )
    }

    // Layer 2: Gemini Live overlay (transcript + controls)
    AnimatedVisibility(
        visible = isGeminiActive,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 },
    ) {
      GeminiOverlay(
          geminiViewModel = geminiViewModel,
          modifier = Modifier.fillMaxSize(),
      )
    }

    // Layer 3: Bottom bar with streaming controls + AI button
    Box(
        modifier = Modifier.fillMaxSize().padding(all = 24.dp),
    ) {
      // Connection status indicator (top-right)
      if (isGeminiActive) {
        GeminiConnectionBadge(
            state = geminiUiState.connectionState,
            modifier = Modifier.align(Alignment.TopEnd).systemBarsPadding(),
        )
      }

      Row(
          modifier =
              Modifier.align(Alignment.BottomCenter)
                  .navigationBarsPadding()
                  .fillMaxWidth()
                  .height(56.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.CenterVertically,
      ) {
        // Stop streaming button
        SwitchButton(
            label = stringResource(R.string.stop_stream_button_title),
            onClick = {
              geminiViewModel.disconnect()
              streamViewModel.stopStream()
              wearablesViewModel.navigateToDeviceSelection()
            },
            isDestructive = true,
            modifier = Modifier.weight(1f),
        )

        // Photo capture button
        CaptureButton(
            onClick = { streamViewModel.capturePhoto() },
        )

        // AI toggle button
        AiToggleButton(
            isActive = isGeminiActive,
            connectionState = geminiUiState.connectionState,
            onClick = {
              if (isGeminiActive) {
                geminiViewModel.disconnect()
              } else {
                geminiViewModel.connect()
              }
            },
        )
      }
    }
  }

  // Photo share dialog
  streamUiState.capturedPhoto?.let { photo ->
    if (streamUiState.isShareDialogVisible) {
      SharePhotoDialog(
          photo = photo,
          onDismiss = { streamViewModel.hideShareDialog() },
          onShare = { bitmap ->
            streamViewModel.sharePhoto(bitmap)
            streamViewModel.hideShareDialog()
          },
      )
    }
  }
}

// --- Gemini Overlay Components ---

@Composable
private fun GeminiOverlay(
    geminiViewModel: GeminiLiveViewModel,
    modifier: Modifier = Modifier,
) {
  val uiState by geminiViewModel.uiState.collectAsStateWithLifecycle()
  val listState = rememberLazyListState()
  val isConnected = uiState.connectionState == SessionState.CONNECTED

  // Auto-scroll when transcript changes (size or content of last entry)
  val lastEntryText = uiState.transcript.lastOrNull()?.text
  LaunchedEffect(uiState.transcript.size, lastEntryText) {
    if (uiState.transcript.isNotEmpty()) {
      listState.animateScrollToItem(uiState.transcript.size - 1)
    }
  }

  Box(modifier = modifier) {
    Column(
        modifier =
            Modifier.fillMaxSize()
                .systemBarsPadding()
                .padding(top = 36.dp, bottom = 80.dp), // top: below status badge, bottom: above stream controls
    ) {
      // Scrollable transcript area
      LazyColumn(
          state = listState,
          modifier =
              Modifier.weight(1f)
                  .fillMaxWidth()
                  .padding(horizontal = 16.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        items(uiState.transcript) { entry ->
          TranscriptBubble(entry = entry)
        }

        // Show active tool call card
        uiState.activeToolCall?.let { toolCall ->
          item {
            ToolCallCard(
                task = toolCall.task,
                status = toolCall.status,
            )
          }
        }
      }

      // Bottom controls: mic + text input (only when connected)
      if (isConnected) {
        GeminiInputControls(
            isRecording = uiState.isRecording,
            onToggleRecording = { geminiViewModel.toggleRecording() },
            onSendText = { geminiViewModel.sendTextMessage(it) },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
      }
    }
  }
}

@Composable
private fun GeminiInputControls(
    isRecording: Boolean,
    onToggleRecording: () -> Unit,
    onSendText: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
  var textInput by remember { mutableStateOf("") }

  Row(
      modifier = modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    // Mic button
    MicButton(
        isRecording = isRecording,
        onClick = onToggleRecording,
    )

    // Text input
    OutlinedTextField(
        value = textInput,
        onValueChange = { textInput = it },
        placeholder = {
          Text(
              stringResource(R.string.gemini_live_text_hint),
              color = Color.White.copy(alpha = 0.6f),
          )
        },
        modifier = Modifier.weight(1f),
        singleLine = true,
        colors =
            OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = AppColor.DeepBlue,
                unfocusedBorderColor = Color.White.copy(alpha = 0.3f),
                cursorColor = AppColor.DeepBlue,
                focusedContainerColor = Color.Black.copy(alpha = 0.4f),
                unfocusedContainerColor = Color.Black.copy(alpha = 0.3f),
            ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions =
            KeyboardActions(
                onSend = {
                  if (textInput.isNotBlank()) {
                    onSendText(textInput)
                    textInput = ""
                  }
                },
            ),
        shape = RoundedCornerShape(24.dp),
    )

    // Send button
    IconButton(
        onClick = {
          if (textInput.isNotBlank()) {
            onSendText(textInput)
            textInput = ""
          }
        },
    ) {
      Icon(
          imageVector = Icons.AutoMirrored.Filled.Send,
          contentDescription = stringResource(R.string.gemini_live_send),
          tint = if (textInput.isNotBlank()) AppColor.DeepBlue else Color.White.copy(alpha = 0.4f),
      )
    }
  }
}

@Composable
private fun AiToggleButton(
    isActive: Boolean,
    connectionState: SessionState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
  val isConnecting = connectionState == SessionState.CONNECTING

  // Pulsing animation when connecting
  val infiniteTransition = rememberInfiniteTransition(label = "aiPulse")
  val pulseScale by
      infiniteTransition.animateFloat(
          initialValue = 1f,
          targetValue = 1.12f,
          animationSpec =
              infiniteRepeatable(
                  animation = tween(800),
                  repeatMode = RepeatMode.Reverse,
              ),
          label = "aiPulseScale",
      )

  val scale = if (isConnecting) pulseScale else 1f
  val containerColor =
      when {
        isConnecting -> AppColor.Yellow
        isActive -> AppColor.Green
        else -> AppColor.DeepBlue
      }

  FloatingActionButton(
      onClick = onClick,
      containerColor = containerColor,
      contentColor = Color.White,
      elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 6.dp),
      modifier = modifier.size(56.dp).scale(scale),
  ) {
    if (isConnecting) {
      CircularProgressIndicator(
          modifier = Modifier.size(24.dp),
          strokeWidth = 2.dp,
          color = Color.White,
      )
    } else {
      Icon(
          imageVector = Icons.Default.SmartToy,
          contentDescription = stringResource(R.string.gemini_live_ai_button),
          modifier = Modifier.size(28.dp),
      )
    }
  }
}

@Composable
private fun GeminiConnectionBadge(
    state: SessionState,
    modifier: Modifier = Modifier,
) {
  val color by
      animateColorAsState(
          targetValue =
              when (state) {
                SessionState.CONNECTED -> AppColor.Green
                SessionState.CONNECTING -> AppColor.Yellow
                SessionState.ERROR -> AppColor.Red
                SessionState.DISCONNECTED -> Color.Gray
              },
          label = "connectionColor",
      )

  val label =
      when (state) {
        SessionState.CONNECTED -> stringResource(R.string.gemini_live_connected)
        SessionState.CONNECTING -> stringResource(R.string.gemini_live_connecting)
        SessionState.ERROR -> stringResource(R.string.gemini_live_error)
        SessionState.DISCONNECTED -> stringResource(R.string.gemini_live_disconnected)
      }

  Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier =
          modifier
              .background(
                  color = Color.Black.copy(alpha = 0.5f),
                  shape = RoundedCornerShape(16.dp),
              )
              .padding(horizontal = 12.dp, vertical = 6.dp),
  ) {
    Box(
        modifier = Modifier.size(8.dp).background(color = color, shape = CircleShape),
    )
    Spacer(modifier = Modifier.width(6.dp))
    Text(
        text = label,
        style = MaterialTheme.typography.bodySmall,
        color = Color.White,
    )
  }
}

@Composable
private fun MicButton(
    isRecording: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
  val infiniteTransition = rememberInfiniteTransition(label = "micPulse")
  val pulseScale by
      infiniteTransition.animateFloat(
          initialValue = 1f,
          targetValue = 1.15f,
          animationSpec =
              infiniteRepeatable(
                  animation = tween(600),
                  repeatMode = RepeatMode.Reverse,
              ),
          label = "pulseScale",
      )

  val scale = if (isRecording) pulseScale else 1f
  val containerColor = if (isRecording) AppColor.Red else AppColor.DeepBlue

  FloatingActionButton(
      onClick = onClick,
      containerColor = containerColor,
      contentColor = Color.White,
      elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 4.dp),
      modifier = modifier.size(48.dp).scale(scale),
  ) {
    Icon(
        imageVector = if (isRecording) Icons.Default.Mic else Icons.Default.MicOff,
        contentDescription =
            if (isRecording) stringResource(R.string.gemini_live_stop_mic)
            else stringResource(R.string.gemini_live_start_mic),
        modifier = Modifier.size(24.dp),
    )
  }
}

// --- Transcript + Tool Call Components ---

@Composable
private fun TranscriptBubble(entry: TranscriptEntry, modifier: Modifier = Modifier) {
  val isUser = entry.role == TranscriptRole.USER
  val isTool = entry.role == TranscriptRole.TOOL
  val isSystem = entry.role == TranscriptRole.SYSTEM
  val isLive = !entry.isFinalized

  val alignment = if (isUser) Alignment.End else Alignment.Start
  val backgroundColor =
      when {
        isUser -> AppColor.DeepBlue.copy(alpha = 0.85f)
        isTool -> Color(0xFF2D2D3A).copy(alpha = 0.85f)
        isSystem -> Color.Transparent
        else -> Color(0xFF333344).copy(alpha = 0.85f)
      }

  Column(
      modifier = modifier.fillMaxWidth(),
      horizontalAlignment = alignment,
  ) {
    if (isSystem) {
      Text(
          text = entry.text,
          style = MaterialTheme.typography.bodySmall,
          color = Color.White.copy(alpha = 0.7f),
          fontStyle = FontStyle.Italic,
          modifier = Modifier.padding(vertical = 2.dp),
      )
    } else {
      Card(
          shape = RoundedCornerShape(16.dp),
          colors = CardDefaults.cardColors(containerColor = backgroundColor),
          modifier = Modifier.fillMaxWidth(if (isUser || isTool) 0.8f else 0.85f),
      ) {
        Column(modifier = Modifier.padding(10.dp)) {
          if (isTool) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Icon(
                  imageVector = Icons.Default.Build,
                  contentDescription = null,
                  tint = AppColor.Yellow,
                  modifier = Modifier.size(12.dp),
              )
              Spacer(modifier = Modifier.width(4.dp))
              Text(
                  text = stringResource(R.string.gemini_live_tool_call),
                  style = MaterialTheme.typography.labelSmall,
                  color = AppColor.Yellow,
                  fontWeight = FontWeight.Bold,
              )
            }
            Spacer(modifier = Modifier.height(2.dp))
          }

          Text(
              text = entry.text + if (isLive) " ▍" else "",
              style = MaterialTheme.typography.bodySmall,
              color = if (isLive) Color.White.copy(alpha = 0.9f) else Color.White,
          )
        }
      }
    }
  }
}

@Composable
private fun ToolCallCard(
    task: String,
    status: ToolCallStatus,
    modifier: Modifier = Modifier,
) {
  Card(
      shape = RoundedCornerShape(12.dp),
      colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E2F).copy(alpha = 0.9f)),
      modifier = modifier.fillMaxWidth(),
  ) {
    Row(
        modifier = Modifier.padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      when (status) {
        ToolCallStatus.EXECUTING -> {
          CircularProgressIndicator(
              modifier = Modifier.size(16.dp),
              strokeWidth = 2.dp,
              color = AppColor.Yellow,
          )
        }
        ToolCallStatus.COMPLETED -> {
          Icon(
              imageVector = Icons.Default.Build,
              contentDescription = null,
              tint = AppColor.Green,
              modifier = Modifier.size(16.dp),
          )
        }
        ToolCallStatus.FAILED -> {
          Icon(
              imageVector = Icons.Default.Build,
              contentDescription = null,
              tint = AppColor.Red,
              modifier = Modifier.size(16.dp),
          )
        }
      }

      Spacer(modifier = Modifier.width(8.dp))

      Column {
        Text(
            text = stringResource(R.string.gemini_live_fulfilling),
            style = MaterialTheme.typography.labelSmall,
            color = AppColor.Yellow,
        )
        Text(
            text = task,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White,
        )
      }
    }
  }
}
