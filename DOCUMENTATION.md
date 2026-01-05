# Software Architecture

## 1. High-Level Overview
USSOI-CAM is a native Android application designed to bridge drone hardware with a Ground Station (GS) over the internet. The app uses a **Foreground Service** architecture to ensure persistent telemetry and video transmission even when the screen is locked or the app is minimized.

## 2. Core Components

### A. UI Layer (`/app/src/main/java/com/ussoi/ui`)
* **LoginActivity**: Handles user authentication with the Ground Station. Obtains the `sessionKey` via HTTP POST.
* **MainActivity**: The dashboard showing connection status, current IP, and control buttons (Start/Stop Stream).
* **CameraPreview**: Renders the local camera view using Android `Camera2` API or `SurfaceView`.

### B. Service Layer (`/app/src/main/java/com/ussoi/service`)
* **UssoiBackgroundService**: The heart of the application.
    * **Responsibility**: Manages the WebSocket connection, polls GPS/Battery sensors, and keeps the CPU awake.
    * **Lifecycle**: Started as a *Foreground Service* with a persistent notification.

### C. Network Layer
* **WebSocketClient**: Manages the persistent connection to `ws://<host>`.
* **TelemetryEncoder**: Converts Java objects (Battery, GPS) into the custom Little-Endian binary format required by the GS.
* **VideoStreamer**: Captures frames from the camera and pipes them to the socket (or UDP stream).

## 3. Data Flow

### Telemetry Loop (Every 5 seconds)
1.  **Sensors**: App queries `LocationManager` for Lat/Lon and `BatteryManager` for voltage/current.
2.  **Encoding**: Data is passed to the `TelemetryEncoder`.
    * *Note:* Floats and Integers are converted to Little Endian hex strings.
3.  **Transport**: The hex string is wrapped in a JSON object (`{"type":"clientStats", "hex":...}`) and sent via WebSocket.

### Video Pipeline
1.  **Capture**: Camera hardware captures YUV/Surface texture.
2.  **Encode**: MediaCodec compresses stream (H.264).
3.  **Transmission**: NAL units are packetized and sent to the Ground Station.

## 4. Key Classes & Responsibilities

| Class Name | Responsibility |
| :--- | :--- |
| `TelemetryManager` | Aggregates sensor data from Android APIs. |
| `HexUtil` | Helper class for Little-Endian byte conversion. |
| `SocketHandler` | Singleton wrapper for the WebSocket connection state. |
| `PermissionManager` | Handles runtime permissions (Camera, Location, FG Service). |

## 5. Security & Auth
* **Session Handshake**: The app never stores the password. It sends credentials once to `/authentication` and holds the returned `sessionKey` in memory for the WebSocket handshake.