# USSOI-CAM

> **⚠️ Important Note:**
> *  See https://github.com/nikipoatgit/USSOI-CAM for Android Client  
> *  See https://github.com/nikipoatgit/GS_For_USSOI for Host Implementation
> *  See https://github.com/nikipoatgit/FlightController_H743 if want make ur own FC

#### Future Updates
- Ability to Change UART Tunnel IP/URL
- Support for Multiple BT Modules (1-3)
- P2P Connection Support (via NAT Traversal)

#
 ![ussoiUseExample](doc/ussoiUseExample.png)

## 📑 Table of Contents

- [System Architecture](#system-architecture)
- [App Features](#app-features)
- [API Endpoints](#-api-endpoints)
  - [Streaming API](#1-streaming-api-wsipstreaming)
  - [Control API](#2-control-api-wsipcontrolapi)
  - [Telemetry API](#3-telemetry-api-wsiptelemetry)
- [Getting Started](#get_started )
---

##  System Architecture

![USSOI CAMfeed Flowchart](doc/ussoiFlowchart.png)


## APP Features
* **Background Operation:** The service runs persistently in the background, even when the screen is off. A persistent notification is required by Android to maintain this functionality.
* **Authentication:** Credentials must be configured via the GS login interface before the application can establish WebSocket connections to the Ground Server.
( Default values roomId : nikipo, roomPwd : nikipo ).


##  API Endpoints

The system connects three  WebSocket endpoints (Assuming Host is listening on it )for different functionalities.


---
## Get Started 
  * Install Application From [Release](https://github.com/nikipoatgit/USSOI-CAM/releases/) or Clone repo in Android Studios Code : `https://github.com/nikipoatgit/USSOI-CAM.git`
  * Select Buttons according to use Case :
    * Case USB :  
        1. Enable Mavlink  
        2.  Enable video streaming if required ( if want to switch to  TURN / MSE turn it off and use api to enable streaming ) 
        3. Enter Baudrate 
        4. Enter URL
        5. Start Service 
        6. <img src="doc/ussoiMainUI.jpg" alt="USSOI user interface" height="500px">

    * Case BT : 
        1. Enable Mavlink  
        2.  Enable video streaming if required ( if want to switch to  TURN / MSE turn it off and use api to enable streaming ) 
        3. Enable BT 
        4. Enter URL
        5. Start Service
        6. Select Paired BT device (RFCOMM)
        7. <img src="doc\ussoiBTUI.jpg" alt="USSOI user interface" height="600px">
    * Illustration:    
      ![ussoiUseExample](doc/ussoiUseExample.png)

## end of file 
