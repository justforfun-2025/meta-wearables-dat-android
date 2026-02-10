# AndroidClaw Client

> **See it. Say it. Done.** — AI glasses that understand your world and act on it.

A hands-free AI assistant for **Meta Ray-Ban smart glasses** that combines real-time voice conversation and live camera vision (powered by **Gemini Live API**) with real-world task execution (powered by **[OpenClaw](https://github.com/justforfun-2025/androidclaw)**). Built on the official [Meta Wearables Device Access Toolkit](https://wearables.developer.meta.com/docs/develop/).

## Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│  Meta Ray-Ban Smart Glasses                                         │
│  ┌──────────┐  ┌──────────┐                                         │
│  │  Camera   │  │   Mic    │                                         │
│  └────┬─────┘  └────┬─────┘                                         │
│       │ video        │ audio                                         │
└───────┼──────────────┼──────────────────────────────────────────────┘
        │              │          Bluetooth
┌───────▼──────────────▼──────────────────────────────────────────────┐
│  Android Phone (this app)                                           │
│                                                                     │
│  ┌─────────────────────────────────────────────────────────────┐    │
│  │  StreamScreen (Jetpack Compose)                             │    │
│  │  ┌───────────────────┐  ┌─────────────────────────────────┐ │    │
│  │  │  Live Video Feed  │  │  Gemini AI Overlay              │ │    │
│  │  │  (full-screen)    │  │  - Live transcription           │ │    │
│  │  │                   │  │  - Conversation transcript       │ │    │
│  │  │  Video frames ────┼──│  - Tool call status             │ │    │
│  │  │  sent to Gemini   │  │  - Mic / text input controls    │ │    │
│  │  │  at ~1fps         │  │                                 │ │    │
│  │  └───────────────────┘  └─────────────────────────────────┘ │    │
│  └──────────────────────────┬──────────────────────────────────┘    │
│                             │                                       │
│  ┌──────────────────────────▼──────────────────────────────────┐    │
│  │  GeminiLiveViewModel                                        │    │
│  │  - Audio capture (16kHz PCM) → Gemini                       │    │
│  │  - Audio playback (24kHz PCM) ← Gemini                      │    │
│  │  - Video frame forwarding → Gemini                          │    │
│  │  - Tool call routing → OpenClawClient                       │    │
│  │  - AEC + Noise Suppression                                  │    │
│  └───────┬─────────────────────────────────┬───────────────────┘    │
│          │ WebSocket                       │ HTTP POST              │
│          │ (bidirectional audio+vision)    │ /v1/chat/completions   │
└──────────┼─────────────────────────────────┼────────────────────────┘
           │                                 │
           ▼                                 ▼
┌─────────────────────┐     ┌──────────────────────────────────────┐
│  Gemini Live API    │     │  OpenClaw Gateway (AndroidClaw)      │
│                     │     │  github.com/justforfun-2025/         │
│  Model: gemini-2.5  │     │           androidclaw                │
│  -flash-native-     │     │                                      │
│   audio-preview     │     │  Runs in AVF Linux VM on Pixel 9     │
│                     │     │  - Node.js 22 + OpenClaw 2026.2.9    │
│  Capabilities:      │     │  - Playwright + Chromium (browser)   │
│  - Native audio I/O │     │  - 56+ connected skills              │
│  - Vision input     │     │  - ws://192.168.0.2:18790            │
│  - Function calling │     │                                      │
│  - Live transcripts │     │  Skills: messaging, web search,      │
│                     │     │  lists, reminders, notes, smart      │
│                     │     │  home, app control, research, ...    │
└─────────────────────┘     └──────────────────────────────────────┘
```

## How It Works

1. **User speaks** to their Meta Ray-Ban glasses → audio streams to the phone via Bluetooth
2. **Audio + video frames** are forwarded to Gemini Live via WebSocket in real time
3. **Gemini processes** the multimodal input (voice + vision) and responds with spoken audio
4. If the user requests an **action** (send a message, search the web, add to a list, etc.):
   - Gemini speaks a verbal acknowledgment: *"Sure, adding that to your list."*
   - Gemini invokes the `execute` tool with a task description
   - `OpenClawClient` sends an HTTP POST to the [AndroidClaw](https://github.com/justforfun-2025/androidclaw) gateway
   - OpenClaw executes the task using its 56+ connected skills (including browser automation)
   - The result returns to Gemini, which speaks the confirmation
5. If the user asks a **question** Gemini can answer directly (identify an object, read a sign, general knowledge), it responds without calling the tool

## Key Components

```
samples/CameraAccess/app/src/main/java/.../cameraaccess/
├── gemini/
│   ├── GeminiLiveSession.kt      # WebSocket client for Gemini Live API
│   │                              # - Connection lifecycle & setup message
│   │                              # - Tool declaration (execute function)
│   │                              # - Audio/image/text sending
│   │                              # - Server message parsing (audio, tool calls,
│   │                              #   transcriptions, interruptions)
│   │
│   ├── GeminiLiveViewModel.kt    # Session controller (AndroidViewModel)
│   │                              # - Mic capture: 16kHz mono PCM → Gemini
│   │                              # - Speaker playback: 24kHz mono PCM (sequential Channel)
│   │                              # - Video frame forwarding: ~1fps JPEG → Gemini
│   │                              # - Tool call routing → OpenClawClient
│   │                              # - AEC + Noise Suppression
│   │                              # - Live transcript management
│   │
│   ├── GeminiLiveUiState.kt      # UI state: connection, transcript, tool calls
│   │
│   └── OpenClawClient.kt         # HTTP client for OpenClaw gateway
│                                  # - POST /v1/chat/completions (OpenAI-compatible)
│                                  # - Bearer token auth + session key header
│                                  # - Response parsing: choices[0].message.content
│
├── ui/
│   ├── StreamScreen.kt            # Unified video + Gemini overlay UI
│   │                              # - Full-screen live video from glasses
│   │                              # - Transparent Gemini chat overlay
│   │                              # - Live transcription with cursor indicator
│   │                              # - Mic toggle + text input controls
│   │                              # - Tool call status card
│   │
│   ├── CameraAccessScaffold.kt   # Top-level navigation scaffold
│   └── ...                        # Other UI screens (home, non-stream, etc.)
│
├── stream/                        # DAT video streaming logic
├── wearables/                     # DAT device connection management
├── mockdevicekit/                 # Mock device for development/testing
└── MainActivity.kt                # Entry point, permissions handling
```

## Tech Stack

| Layer | Technology | Details |
|-------|-----------|---------|
| **Glasses** | Meta Ray-Ban | Camera + microphone via Bluetooth |
| **SDK** | Meta Wearables DAT 0.4.0 | Video streaming, photo capture, device management |
| **AI Model** | Gemini 2.5 Flash (native audio preview) | Real-time multimodal: voice I/O, vision, function calling |
| **AI Protocol** | WebSocket (Gemini Live API) | Bidirectional streaming with `BidiGenerateContent` |
| **Action Gateway** | [AndroidClaw](https://github.com/justforfun-2025/androidclaw) (OpenClaw) | AVF Linux VM, Node.js 22, Playwright, 56+ skills |
| **Gateway Protocol** | HTTP POST `/v1/chat/completions` | OpenAI-compatible, Bearer auth, session continuity |
| **UI** | Jetpack Compose + Material 3 | Video overlay with transparent chat, live transcription |
| **Audio** | AudioRecord / AudioTrack | 16kHz capture, 24kHz playback, AEC + noise suppression |
| **Networking** | OkHttp 4.12 | WebSocket (Gemini) + HTTP (OpenClaw) |
| **Architecture** | MVVM | ViewModel + StateFlow + Compose |
| **Language** | Kotlin | Coroutines, Channels, Flow |

## Gemini Live Configuration

The setup message configures Gemini with:

- **Model**: `gemini-2.5-flash-native-audio-preview-12-2025`
- **Voice**: `Puck`
- **Response modality**: Audio only
- **Thinking budget**: 0 (instant responses)
- **Tool**: `execute(task: string)` with `BLOCKING` behavior
- **VAD**: Automatic activity detection with high start sensitivity, low end sensitivity
- **Transcription**: Both input and output audio transcription enabled
- **System instruction**: Smart routing — answers questions directly, delegates actions to OpenClaw

## Audio Pipeline

```
┌──────────────┐    16kHz PCM     ┌──────────────┐    Audio chunks    ┌──────────┐
│  Microphone  │ ──────────────▶  │  AudioRecord  │ ────────────────▶ │  Gemini  │
│  (glasses)   │    100ms chunks  │  + AEC + NS   │   Base64 encoded  │  Live    │
└──────────────┘                  └──────────────┘                    │  API     │
                                                                      │          │
┌──────────────┐    24kHz PCM     ┌──────────────┐    Audio chunks    │          │
│  Speaker     │ ◀──────────────  │  AudioTrack   │ ◀──────────────── │          │
│  (phone)     │    Sequential    │  (voice comm) │   Base64 decoded  │          │
└──────────────┘    Channel       └──────────────┘                    └──────────┘
```

- **Echo cancellation**: `VOICE_COMMUNICATION` audio source + `AcousticEchoCanceler`
- **Noise suppression**: `NoiseSuppressor` attached to AudioRecord session
- **Playback**: Single sequential coroutine drains a `Channel<ByteArray>` to prevent AudioTrack race conditions

## Setup

### Prerequisites

- Android Studio (2021.3.1+)
- Android SDK 31+ (Android 12.0+)
- Meta Ray-Ban smart glasses (or use MockDeviceKit for development)
- [AndroidClaw](https://github.com/justforfun-2025/androidclaw) gateway running (AVF Linux VM on Pixel 9)
- Gemini API key with access to the Live API

### Configuration

Add to `samples/CameraAccess/local.properties`:

```properties
# GitHub Packages token (read:packages scope) for Meta DAT SDK
github_token=ghp_your_token_here

# Gemini API key
gemini_api_key=your_gemini_api_key

# OpenClaw gateway (AndroidClaw running in AVF VM)
openclaw_url=http://192.168.0.2:18790
openclaw_token=androidclaw-local-token
```

### Build & Run

```bash
cd samples/CameraAccess
./gradlew installDebug
```

### Network Setup

The app connects to the OpenClaw gateway at `http://192.168.0.2:18790` (the AVF Linux VM's bridge IP). Cleartext HTTP is permitted via `network_security_config.xml`.

If using ADB reverse instead of the VM bridge:
```bash
adb reverse tcp:18790 tcp:18790
# Then set openclaw_url=http://127.0.0.1:18790 in local.properties
```

### Permissions

The app requires:
- `BLUETOOTH` / `BLUETOOTH_CONNECT` — Meta glasses connection
- `INTERNET` — Gemini WebSocket + OpenClaw HTTP
- `RECORD_AUDIO` — Microphone capture for voice input

## Related Projects

- **[AndroidClaw](https://github.com/justforfun-2025/androidclaw)** — OpenClaw AI gateway running on-device in an AVF Linux VM with Playwright browser tooling
- **[VisionClaw](https://github.com/sseanliu/VisionClaw)** — The iOS counterpart with the same Gemini Live + OpenClaw architecture
- **[Meta Wearables DAT](https://github.com/facebook/meta-wearables-dat-android)** — Official SDK for Meta AI glasses

## Acknowledgments

- **[VisionClaw](https://github.com/sseanliu/VisionClaw)** — The iOS implementation that inspired this project's Gemini Live + OpenClaw architecture for smart glasses.
- **[Meta Wearables Device Access Toolkit](https://wearables.developer.meta.com/)** — For providing the SDK that makes camera and sensor access on Meta Ray-Ban glasses possible.
- **[Google Gemini](https://ai.google.dev/)** — For the Live API enabling real-time multimodal voice and vision interaction.
- **[OpenClaw](https://github.com/openclaw/openclaw)** — The AI agent framework powering task execution with 56+ skills.

## License

This source code is licensed under the license found in the LICENSE file in the root directory of this source tree.
