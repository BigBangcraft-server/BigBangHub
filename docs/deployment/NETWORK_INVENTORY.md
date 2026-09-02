# BigBangCraft Network Inventory — Production Deployment

This inventory documents the actual infrastructure, processes, network topology, runtime parameters, and server directories for the deployment of **BigBangHub 0.4.0**.

---

## 1. Network Topology Overview

```
                        [ Internet (0.0.0.0:25565) ]
                                     │
                                     ▼
               Host: ubuntu2 (WireGuard IP: 10.8.0.1/24)
                        Velocity Proxy 4.1.1
               (tmux: /tmp/tmux_shared session "proxy")
                                     │
                 WireGuard VPN (wg0, 51820/UDP, MTU 1380)
                                     │
                                     ▼
               Host: brainiac (WireGuard IP: 10.8.0.2/24)
                 Paper 26.2 Servers (MC 26.2, Java 25)
               (tmux: /tmp/tmux_shared sessions per server)
                 ┌───────────────────┬───────────────────┐
                 │                   │                   │
                 ▼                   ▼                   ▼
            hubminigame         campominado           bedwars / hg
           (10.8.0.2:25565)    (10.8.0.2:25567)     (10.8.0.2:25566 / 25568)
              Role: HUB        Role: MINIGAME
```

---

## 2. Host Specifications

### Host 1: `ubuntu2` (Proxy Node)
* **Hostname**: `instance-20250405-1210` (`ubuntu2`)
* **User**: `ubuntu`
* **Network Interfaces**:
  * Public / eth: `0.0.0.0:25565` (Minecraft TCP), `0.0.0.0:24454` (Simple Voice Chat UDP)
  * WireGuard `wg0`: `10.8.0.1/24` (Port 51820)
* **Operating System**: Linux aarch64 (Ubuntu)
* **Java Runtime**: OpenJDK Temurin 25.0.4+7-LTS (`/home/ubuntu/.sdkman/candidates/java/25.0.4-tem/bin/java`)
* **Process Manager**: tmux (`/tmp/tmux_shared`, session `proxy`)

### Host 2: `brainiac` (Backend Node)
* **Hostname**: `brainiac`
* **User**: `brainiac`
* **Total RAM**: 22 GiB physical
* **Network Interfaces**:
  * WireGuard `wg0`: `10.8.0.2/24` (Port 38720)
  * Backends bind strictly to `10.8.0.2` (not exposed to public internet)
* **Operating System**: Linux x86_64 7.0.0-30-generic (Ubuntu)
* **Java Runtime**: OpenJDK Temurin 25.0.3+9-LTS (`/home/brainiac/.sdkman/candidates/java/25.0.3-tem/bin/java`)
* **Process Manager**: tmux (`/tmp/tmux_shared`, sessions: `hubminigame`, `bedwars`, `campominado`, `hg`)

---

## 3. Server Process Mapping

### 3.1. Velocity Proxy (`ubuntu2`)
* **Service / Session**: `tmux -S /tmp/tmux_shared -s proxy`
* **Directory**: `/home/ubuntu/proxy`
* **Port**: `25565` (binds `0.0.0.0:25565`)
* **Velocity Version**: `4.1.1`
* **RAM Allocation**: `-Xms1G -Xmx2G -Dvelocity.max-plugin-message-payload-size=16777216`
* **Startup Script**: `/home/ubuntu/proxy/start_tmux.sh` -> `/home/ubuntu/proxy/startserver.sh` (automatic restart loop)
* **Configuration File**: `/home/ubuntu/proxy/velocity.toml`
* **Player Info Forwarding**: `bungeeguard` (secret in `/home/ubuntu/proxy/forwarding.secret`)
* **Defined Servers**:
  * `hubminigame` -> `10.8.0.2:25565` (primary hub / try target)
  * `campominado` -> `10.8.0.2:25567`
  * `bedwars` -> `10.8.0.2:25566`
  * `hg` -> `10.8.0.2:25568`
  * `play` -> `10.8.0.2:25565` (alias)
* **Existing Plugins**:
  * `AdvancedServerList-Velocity-5.8.0.jar`
  * `LuckPerms-Velocity.jar`
  * `SkinsRestorer.jar` (15.12.4)
  * `voicechat-velocity-2.6.18.jar`
  * `nLogin.jar` (2.0.19)
  * `hub.jar` (dev by uebliche)
* **Logs**: `/home/ubuntu/proxy/logs/latest.log`

### 3.2. Hub Server (`hubminigame` on `brainiac`)
* **Service / Session**: `tmux -S /tmp/tmux_shared -s hubminigame`
* **Directory**: `/home/brainiac/bigbangcraft/hubminigame`
* **Bind Address & Port**: `10.8.0.2:25565`
* **Paper Version**: `Paper 26.2-120-main@1797fbc` (Minecraft 26.2, API 26.2-stable)
* **RAM Allocation**: `-Xms512M -Xmx1536M` (Aikar G1GC flags)
* **Startup Script**: `/home/brainiac/bigbangcraft/hubminigame/startserver.sh` (managed by `/home/brainiac/bigbangcraft/start.sh`)
* **Existing Plugins**:
  * `BungeeGuard.jar` (1.4.6)
  * `EasyCommandBlocker-1.18.1.jar`
  * `FancyHolograms-2.11.0+193.jar`
  * `FancyNpcs-2.11.0+370.jar`
  * `FastAsyncWorldEdit-Paper-2.15.4.jar`
  * `HubMinigame.jar` (1.0.0)
  * `LuckPerms-Bukkit-5.5.9.jar`
  * `Vault.jar` (1.7.3-b131)
  * `VoidGen-2.3.8.jar`
  * `WorldGuard-bukkit-7.0.18.jar`
  * `nLogin-2.0.19.jar`
  * `spark`
* **World**: `world` (VoidGen void generator, spawn at `0.5, 69.0, 0.5`)
* **Logs**: `/home/brainiac/bigbangcraft/hubminigame/logs/latest.log`

### 3.3. Minigame Server: Campo Minado (`campominado` on `brainiac`)
* **Service / Session**: `tmux -S /tmp/tmux_shared -s campominado`
* **Directory**: `/home/brainiac/bigbangcraft/minigames/campominado`
* **Bind Address & Port**: `10.8.0.2:25567`
* **Paper Version**: `Paper 26.2-120-main@1797fbc`
* **RAM Allocation**: `-Xms512M -Xmx2048M` (Aikar G1GC flags)
* **Startup Script**: `/home/brainiac/bigbangcraft/minigames/campominado/startserver.sh` (automatic restart loop)
* **Existing Plugins**:
  * `BigBangMinefield-1.0.0.jar` (custom Campo Minado game plugin)
  * `BungeeGuard.jar` (1.4.6)
  * `EasyCommandBlocker-1.18.1.jar`
  * `worldedit-bukkit-7.4.5.jar`
  * `spark`
* **Arenas Configured**: `arena_01.json`, `arena_02.json`
* **Logs**: `/home/brainiac/bigbangcraft/minigames/campominado/logs/latest.log`

### 3.4. Other Minigames on `brainiac`
* **BedWars**:
  * Directory: `/home/brainiac/bigbangcraft/minigames/bedward`
  * Port: `10.8.0.2:25566`
  * Session: `bedwars`
  * RAM: `-Xms512M -Xmx2048M`
* **HG (Hunger Games)**:
  * Directory: `/home/brainiac/bigbangcraft/minigames/hg`
  * Port: `10.8.0.2:25568`
  * Session: `hg`
  * RAM: `-Xms1024M -Xmx3072M`

---

## 4. Control and Lifecycle Procedures

### 4.1. Velocity Control (`ubuntu2`)
* **Graceful Restart**:
  ```bash
  tmux -S /tmp/tmux_shared send-keys -t proxy "end" Enter
  ```
  *(The `startserver.sh` while loop automatically starts Velocity back up in 2 seconds).*
* **Attach to Console**:
  ```bash
  tmux -S /tmp/tmux_shared attach -t proxy
  ```

### 4.2. Backend Control (`brainiac`)
* **Graceful Restart Single Backend**:
  ```bash
  tmux -S /tmp/tmux_shared send-keys -t hubminigame "stop" Enter
  tmux -S /tmp/tmux_shared send-keys -t campominado "stop" Enter
  ```
  *(The `startserver.sh` while loop automatically starts Paper back up in 2 seconds).*
* **Attach to Console**:
  ```bash
  tmux -S /tmp/tmux_shared attach -t <hubminigame|campominado|bedwars|hg>
  ```
* **Full Network Staggered Startup**:
  ```bash
  /home/brainiac/bigbangcraft/start.sh
  ```

---

## 5. Security & Isolation Invariants
* All backend servers bind strictly to `server-ip=10.8.0.2` on the WireGuard VPN interface.
* Firewall rules on `brainiac` (`BBC_INPUT` chain) DROP unsolicited traffic and only allow `10.8.0.1` (`ubuntu2`) to reach ports `25565-25568`.
* All forwarding authentication tokens (`forwarding.secret`) are protected with `chmod 600` and are never committed to version control.
