<div align="center">
  <h1>NearChat</h1>
  <p><strong>A fast, completely offline, decentralized chat application for Android powered by Bluetooth Classic (RFCOMM).</strong></p>

  <p>
    <img src="https://img.shields.io/badge/Kotlin-100%25-B125EA?style=for-the-badge&logo=kotlin" alt="Kotlin" />
    <img src="https://img.shields.io/badge/Android-3DDC84?style=for-the-badge&logo=android&logoColor=white" alt="Android" />
    <img src="https://img.shields.io/badge/Jetpack%20Compose-4285F4?style=for-the-badge&logo=jetpackcompose&logoColor=white" alt="Jetpack Compose" />
  </p>
</div>

<br/>

**NearChat** enables seamless peer-to-peer and group messaging without the need for Wi-Fi or cellular networks. Built with modern Android development practices, it is designed to be highly reliable, securely managing complex socket lifecycles, robust connection handshakes, and strict host-member permission flows.

---

## Table of Contents
- [Key Features](#-key-features)
- [Tech Stack & Architecture](#️-tech-stack--architecture)
- [System Design & Core Mechanisms](#-system-design--core-mechanisms)
  - [Group Chat Setup: Star Topology](#1-group-chat-setup-star-topology)
  - [Dynamic Handshake & OS Caching Fallback](#2-dynamic-handshake--os-caching-fallback)
- [User Flow & Navigation](#-user-flow--navigation)
- [Screenshots](#-screenshots)
- [Technical Challenges Solved](#-technical-challenges-solved)
- [Getting Started](#-getting-started)

---

## Key Features

- **Completely Decentralized:** Chat freely anywhere you go—no internet, Wi-Fi, or cellular network required.
- **1:1 Peer-to-Peer Chat:** Instantly discover nearby devices and establish secure, bi-directional RFCOMM sockets.
- **Group Chat (Star Topology):** Host local group chats where the Host acts as a central relay, seamlessly broadcasting messages to all connected members simultaneously.
- **Permission-Based Lobbies:** Group hosts maintain full control over their lobbies, featuring real-time Accept/Decline dialogs for incoming connection requests.
- **Robust Socket Lifecycle Management:** Prevents memory leaks and zombie sockets when navigating across screens, backgrounding the app, or abruptly disconnecting.
- **Dynamic Handshake Protocol:** Automatically resolves Android's aggressive Bluetooth name caching by silently intercepting and redirecting outdated connection attempts.

---

## Tech Stack & Architecture

NearChat is built utilizing **Clean Architecture** principles and the **MVVM** pattern to ensure separation of concerns, testability, and a highly responsive UI.

- **Language:** Kotlin
- **UI Framework:** Jetpack Compose (Declarative UI)
- **Asynchronous Programming:** Kotlin Coroutines & `StateFlow` / `SharedFlow`
- **Dependency Injection:** Dagger Hilt
- **Hardware Integration:** Android OS `BluetoothAdapter`, `BluetoothServerSocket`, `BluetoothSocket`

### Architecture Overview

```mermaid
graph TD
    UI[📱 UI Layer<br/>Jetpack Compose] -->|Events| VM[ViewModels]
    VM -->|Observes| State[StateFlow / SharedFlow]
    State --> UI
    
    VM -->|Invokes| UC[Use Cases / Interactors]
    UC -->Repo[Data Repositories]
    
    Repo --> Local[Local Data Source<br/>SharedPrefs]
    Repo --> BT1[1:1 Bluetooth Data Source]
    Repo --> BT2[Group Bluetooth Data Source]
    
    BT1 --> AndroidBT[Android Bluetooth API<br/>RFCOMM Sockets]
    BT2 --> AndroidBT
    
    style UI fill:#e3f2fd,stroke:#1e88e5,stroke-width:2px,color:#000
    style VM fill:#fff3e0,stroke:#fb8c00,stroke-width:2px,color:#000
    style Repo fill:#e8f5e9,stroke:#43a047,stroke-width:2px,color:#000
    style AndroidBT fill:#fce4ec,stroke:#d81b60,stroke-width:2px,color:#000
```

---

## System Design & Core Mechanisms

### 1. Group Chat Setup: Star Topology

Due to the hardware limitations of Bluetooth Classic, establishing a true decentralized, device-to-device mesh network is highly restrictive uniformly across all Android devices. NearChat circumvents this by implementing a **Star Topology** for group communications.

```mermaid
graph TD
    Host((👑 Host Device<br/>Relay Server))
    Member1(📱 Member 1)
    Member2(📱 Member 2)
    Member3(📱 Member 3)

    Host <-->|RFCOMM Socket 1| Member1
    Host <-->|RFCOMM Socket 2| Member2
    Host <-->|RFCOMM Socket 3| Member3

    style Host fill:#bbdefb,stroke:#1976d2,stroke-width:3px,color:#000
    style Member1 fill:#f5f5f5,stroke:#9e9e9e,stroke-width:2px,color:#000
    style Member2 fill:#f5f5f5,stroke:#9e9e9e,stroke-width:2px,color:#000
    style Member3 fill:#f5f5f5,stroke:#9e9e9e,stroke-width:2px,color:#000
```

*Mechanism: When Member 1 sends a message, it is transmitted directly to the Host. The Host instantly acts as a relay, broadcasting that message to Member 2 and Member 3 in real-time. This maintains strict synchronization and message ordering across the entire group without requiring members to connect to each other.*

### 2. Dynamic Handshake & OS Caching Fallback

**The Problem:** Android OS aggressively caches Bluetooth device names to preserve battery life. If `User A` alters their broadcast state from `[NC]` (1:1 mode) to `[NC-G]` (Group Host mode), `User C`'s device might still view them as `[NC]` due to the stale cache. 

**The Solution:** NearChat implements a highly resilient, under-the-hood handshake to intercept these cache-miss connection attempts. The application seamlessly negotiates the connection type and redirects the user into the appropriate group lobby without dropping the connection or exposing the error to the user interface.

```mermaid
sequenceDiagram
    participant Client
    participant Host Server

    Client->>Host Server: Discovers stale [NC] cached name
    Client->>Host Server: Sends 1:1 Connection Req (APP_UUID)
    Note over Host Server: Identifies itself as a Group Host
    Host Server-->>Client: Rejects 1:1 Req + Returns [HOSTING_GROUP] Handshake
    Note over Client: Silently aborts 1:1 chat mode workflow
    Client->>Client: Updates UI State: "(Hosting a group)"
    Client->>Host Server: Automatically sends Group Join Req (GROUP_UUID)
    Host Server-->>Client: Host Prompts UI Dialog (Accept/Reject)
    Host Server-->>Client: Emits GROUP_WELCOME Member List
    Note over Client: ✅ Successfully joined Group Lobby!
```

---

## User Flow & Navigation

The flowchart below visualizes how users interact and navigate through NearChat's 1:1 and Group Chat ecosystems, demonstrating the lifecycle and decision points.

```mermaid
flowchart TD
    Home[🏠 Home Screen] -->|Create Group| HostingGroup(👑 Host: Starts Group Server)
    Home -->|Find Devices| Discovery[🔍 Device Discovery Screen]
    
    HostingGroup -.->|Waits for join requests| Lobby(👥 Group Lobby Screen)
    
    Discovery -->|Taps 1:1 Device| WaitAccept{Wait for Accept}
    WaitAccept -->|Accepted| SingleChat(💬 1:1 Chat Screen)
    WaitAccept -->|Declined| CD[1 Min Cooldown]
    
    Discovery -->|Taps Group Host| GroupHandshake{Group Handshake}
    GroupHandshake -->|Request Sent| HostDialog[Host Accept/Reject Dialog]
    HostDialog -->|Host Rejects| Discovery
    HostDialog -->|Host Accepts| Lobby
    
    Lobby -->|Host Clicks 'Start'| GroupChat(🗯️ Group Chat Screen)
    
    style Home fill:#e3f2fd,stroke:#1e88e5,color:#000
    style HostingGroup fill:#fff9c4,stroke:#fbc02d,color:#000
    style GroupChat fill:#c8e6c9,stroke:#388e3c,color:#000
    style SingleChat fill:#c8e6c9,stroke:#388e3c,color:#000
```

---

## Screenshots


---

## Technical Challenges Solved

Building a reliable Bluetooth application involves navigating severe hardware and OS-level constraints. Here is how NearChat handles them:

1. **Zombie Sockets & Memory Leaks:** 
   Bluetooth sockets operate outside the standard Android component lifecycle. NearChat utilizes careful Coroutine scoping and `ViewModel` `onCleared()` overrides to ensure that sockets are safely closed and input/output streams are flushed whenever a user backgrounds the app or loses connection, preventing "address in use" errors on subsequent connections.
2. **Concurrency & Thread Safety:**
   Handling asynchronous byte streams from multiple devices (in a group chat) requires thread synchronization. NearChat uses Kotlin `SharedFlow` to act as an event bus, safely marshaling network callbacks onto the main thread for UI rendering without race conditions.
3. **OS-Level Caching Overrides:**
   Addressed the Android Bluetooth name caching limitation by implementing custom RFCOMM handshake payloads (as detailed in the System Design section).

---

## Getting Started

### Prerequisites
- **Android Studio:** Ladybug or newer.
- **Physical Android Device:** Emulators **do not** properly support Bluetooth RFCOMM hardware discovery.
- Bluetooth must be enabled on the testing devices.
- Permissions requirement:
  - `ACCESS_COARSE_LOCATION` & `ACCESS_FINE_LOCATION` (Required by Android 11 and below for Bluetooth scanning).
  - `BLUETOOTH_SCAN` & `BLUETOOTH_CONNECT` (Utilized for Android 12+).

### Installation
1. Clone the repository:
   ```bash
   git clone https://github.com/yourusername/NearChat.git
   ```
2. Open the project in Android Studio.
3. Build and run the app simultaneously on **at least two physical Android devices**.
4. Allow all permission prompts to enable Bluetooth discovery and start chatting!

---

## Contributing
Contributions, issues, and feature requests are welcome! 
Feel free to check the [issues page](https://github.com/yourusername/NearChat/issues).

<div align="center">
  <p>Built with ❤️ by an Android Developer</p>
</div>
