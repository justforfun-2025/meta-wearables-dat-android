/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// GeminiLiveScreen - Gemini Live Voice Assistant UI
//
// This composable provides the user interface for the Gemini Live voice assistant,
// featuring a real-time conversation transcript, animated microphone toggle button,
// text input fallback, and tool call status display.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Send
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
import androidx.compose.material3.darkColorScheme
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.R
import com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini.GeminiLiveViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini.SessionState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini.ToolCallStatus
import com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini.TranscriptEntry
import com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini.TranscriptRole

@Composable
fun GeminiLiveScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: GeminiLiveViewModel = viewModel(),
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  val listState = rememberLazyListState()

  // Auto-scroll to the latest transcript entry
  LaunchedEffect(uiState.transcript.size) {
    if (uiState.transcript.isNotEmpty()) {
      listState.animateScrollToItem(uiState.transcript.size - 1)
    }
  }

  MaterialTheme(colorScheme = darkColorScheme()) {
    Box(
        modifier = modifier.fillMaxSize().background(Color.Black).systemBarsPadding(),
    ) {
      Column(modifier = Modifier.fillMaxSize()) {
        // Top bar with back button and connection status
        TopBar(
            connectionState = uiState.connectionState,
            onNavigateBack = onNavigateBack,
        )

        // Conversation transcript
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          items(uiState.transcript) { entry -> TranscriptBubble(entry = entry) }

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

        Spacer(modifier = Modifier.height(8.dp))

        // Bottom controls
        BottomControls(
            connectionState = uiState.connectionState,
            isRecording = uiState.isRecording,
            onConnect = { viewModel.connect() },
            onDisconnect = { viewModel.disconnect() },
            onToggleRecording = { viewModel.toggleRecording() },
            onSendText = { viewModel.sendTextMessage(it) },
        )
      }
    }
  }
}

@Composable
private fun TopBar(
    connectionState: SessionState,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
  Row(
      modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    IconButton(onClick = onNavigateBack) {
      Icon(
          imageVector = Icons.AutoMirrored.Filled.ArrowBack,
          contentDescription = stringResource(R.string.gemini_live_back),
          tint = Color.White,
      )
    }

    Text(
        text = stringResource(R.string.gemini_live_title),
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        color = Color.White,
        modifier = Modifier.weight(1f),
    )

    ConnectionIndicator(state = connectionState)
  }
}

@Composable
private fun ConnectionIndicator(state: SessionState, modifier: Modifier = Modifier) {
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

  Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.padding(end = 12.dp)) {
    Box(
        modifier =
            Modifier.size(8.dp).background(color = color, shape = CircleShape),
    )
    Spacer(modifier = Modifier.width(6.dp))
    Text(
        text = label,
        style = MaterialTheme.typography.bodySmall,
        color = color,
    )
  }
}

@Composable
private fun TranscriptBubble(entry: TranscriptEntry, modifier: Modifier = Modifier) {
  val isUser = entry.role == TranscriptRole.USER
  val isTool = entry.role == TranscriptRole.TOOL
  val isSystem = entry.role == TranscriptRole.SYSTEM

  val alignment = if (isUser) Alignment.End else Alignment.Start
  val backgroundColor =
      when {
        isUser -> AppColor.DeepBlue
        isTool -> Color(0xFF2D2D3A)
        isSystem -> Color(0xFF1A1A2E)
        else -> Color(0xFF333344)
      }
  val textColor = Color.White

  Column(
      modifier = modifier.fillMaxWidth(),
      horizontalAlignment = alignment,
  ) {
    if (isSystem) {
      Text(
          text = entry.text,
          style = MaterialTheme.typography.bodySmall,
          color = Color.Gray,
          fontStyle = FontStyle.Italic,
          modifier = Modifier.padding(vertical = 4.dp),
      )
    } else {
      Card(
          shape = RoundedCornerShape(16.dp),
          colors = CardDefaults.cardColors(containerColor = backgroundColor),
          modifier =
              Modifier.fillMaxWidth(if (isUser || isTool) 0.85f else 0.9f),
      ) {
        Column(modifier = Modifier.padding(12.dp)) {
          if (isTool) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Icon(
                  imageVector = Icons.Default.Build,
                  contentDescription = null,
                  tint = AppColor.Yellow,
                  modifier = Modifier.size(14.dp),
              )
              Spacer(modifier = Modifier.width(6.dp))
              Text(
                  text = stringResource(R.string.gemini_live_tool_call),
                  style = MaterialTheme.typography.labelSmall,
                  color = AppColor.Yellow,
                  fontWeight = FontWeight.Bold,
              )
            }
            Spacer(modifier = Modifier.height(4.dp))
          }

          Text(
              text = entry.text,
              style = MaterialTheme.typography.bodyMedium,
              color = textColor,
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
      colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E2F)),
      modifier = modifier.fillMaxWidth(),
  ) {
    Row(
        modifier = Modifier.padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      when (status) {
        ToolCallStatus.EXECUTING -> {
          CircularProgressIndicator(
              modifier = Modifier.size(20.dp),
              strokeWidth = 2.dp,
              color = AppColor.Yellow,
          )
        }
        ToolCallStatus.COMPLETED -> {
          Icon(
              imageVector = Icons.Default.Build,
              contentDescription = null,
              tint = AppColor.Green,
              modifier = Modifier.size(20.dp),
          )
        }
        ToolCallStatus.FAILED -> {
          Icon(
              imageVector = Icons.Default.Build,
              contentDescription = null,
              tint = AppColor.Red,
              modifier = Modifier.size(20.dp),
          )
        }
      }

      Spacer(modifier = Modifier.width(12.dp))

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

@Composable
private fun BottomControls(
    connectionState: SessionState,
    isRecording: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onToggleRecording: () -> Unit,
    onSendText: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
  val isConnected = connectionState == SessionState.CONNECTED

  Column(
      modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp).navigationBarsPadding(),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    if (isConnected) {
      // Text input row
      TextInputRow(onSend = onSendText)

      // Mic and disconnect buttons
      Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.CenterVertically,
      ) {
        // Mic toggle button
        MicButton(
            isRecording = isRecording,
            onClick = onToggleRecording,
        )

        // Disconnect button
        SwitchButton(
            label = stringResource(R.string.gemini_live_disconnect),
            onClick = onDisconnect,
            isDestructive = true,
            modifier = Modifier.weight(1f),
        )
      }
    } else {
      // Connect button
      SwitchButton(
          label = stringResource(R.string.gemini_live_connect),
          onClick = onConnect,
          enabled =
              connectionState == SessionState.DISCONNECTED ||
                  connectionState == SessionState.ERROR,
      )
    }

    Spacer(modifier = Modifier.height(8.dp))
  }
}

@Composable
private fun TextInputRow(onSend: (String) -> Unit, modifier: Modifier = Modifier) {
  var textInput by remember { mutableStateOf("") }

  Row(
      modifier = modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    OutlinedTextField(
        value = textInput,
        onValueChange = { textInput = it },
        placeholder = {
          Text(
              stringResource(R.string.gemini_live_text_hint),
              color = Color.Gray,
          )
        },
        modifier = Modifier.weight(1f),
        singleLine = true,
        colors =
            OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = AppColor.DeepBlue,
                unfocusedBorderColor = Color.DarkGray,
                cursorColor = AppColor.DeepBlue,
            ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions =
            KeyboardActions(
                onSend = {
                  if (textInput.isNotBlank()) {
                    onSend(textInput)
                    textInput = ""
                  }
                },
            ),
        shape = RoundedCornerShape(24.dp),
    )

    IconButton(
        onClick = {
          if (textInput.isNotBlank()) {
            onSend(textInput)
            textInput = ""
          }
        },
    ) {
      Icon(
          imageVector = Icons.Default.Send,
          contentDescription = stringResource(R.string.gemini_live_send),
          tint = if (textInput.isNotBlank()) AppColor.DeepBlue else Color.Gray,
      )
    }
  }
}

@Composable
private fun MicButton(
    isRecording: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
  // Pulsing animation when recording
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
      modifier = modifier.size(56.dp).scale(scale),
  ) {
    Icon(
        imageVector = if (isRecording) Icons.Default.Mic else Icons.Default.MicOff,
        contentDescription =
            if (isRecording) stringResource(R.string.gemini_live_stop_mic)
            else stringResource(R.string.gemini_live_start_mic),
        modifier = Modifier.size(28.dp),
    )
  }
}

