# USSOI-CAM

> **⚠️ Important Note:**
> *  See https://github.com/nikipoatgit/USSOI-CAM for Android Client  
> *  See https://github.com/nikipoatgit/GS_For_USSOI for Host Implementation
> *  See https://github.com/nikipoatgit/FlightController_H743 if want make ur own FC

#### Future Updates
- Ability to Change UART Tunnel IP/URL
- Support for Multiple BT Modules (1-3)
- P2P Connection Support (via NAT Traversal)
- Auto Quality Adjustment and Network usage Priority

### Purpose
- USSOI-CAM provides an affordable alternative to dedicated digital camera and telemetry systems. It enables live camera feed and telemetry forwarding from Android devices to  ground server, delivering the core functionality required for lightweight UAS/CAM deployments.` This project prioritizes cost-effectiveness and simplicity  `.it may not include all features available in commercial products. See the documentation for known limitations and configuration options.


#
 ![ussoiUseExample](doc/ussoiUseExample.png)

## 📑 Table of Contents
- [Documentation](https://github.com/nikipoatgit/USSOI-CAM/wiki)
- [System Architecture](#system-architecture)
- [App Features](#app-features)
- [API Endpoints](#api-endpoints)
- [Getting Started](#get-started )
---

##  System Architecture

![USSOI CAMfeed Flowchart](doc/ussoiFlowchart.png)


## APP Features
* **Background Operation:** The service runs persistently in the background, even when the screen is off. A persistent notification is required by Android to maintain this functionality.
* **Authentication:** Credentials must be configured via the GS login interface before the application can establish WebSocket connections to the Ground Server.
( Default values roomId : nikipo, roomPwd : nikipo ).


## API Endpoints

HTTP
- Endpoint: `http://<host>/authentication`
- Method: `POST`
- Description: Client login — returns a long-lived `sessionKey` required for WebSocket connections.
- Example response:

```json
{"sessionKey":"<non-empty-string>"}
```

WebSocket
- Endpoint: `ws://<host>/uartunnel>` for UART Tunnel
- Endpoint: `ws://<host>/control/client?sessionKey=<sessionKey>`
- Description: Connect using the `sessionKey` query parameter (alternatively send the `sessionKey` as the first message after connecting).
- After connecting the client sends periodic telemetry messages (every 5 seconds).
- Example message:

```json
{"type":"clientStats","hex":"0000320000C841FFABFFE5FF000023B95B4040585B4100889E3B69DB43D5C21F35404BCD1E6805C353409A9959408E66F13DCDCCCCCCCC2474400"}
```

see [Documentation](https://github.com/nikipoatgit/USSOI-CAM/wiki) for more details


## Get Started 
  * Install Application From [Release](https://github.com/nikipoatgit/USSOI-CAM/releases/) or Clone repo in Android Studios Code : `https://github.com/nikipoatgit/USSOI-CAM.git`
  * Select Buttons according to use Case :
    <div> 
      <img src="doc/ussoiMainUI.jpg" alt="USSOI user interface" height="500px">
      <img src="doc/ussoiBTUI.jpg" alt="USSOI user interface" height="500px">
    </div>

  * Illustration:    
      ![ussoiUseExample](doc/ussoiUseExample.png)

## end of file 
