# BigBangHub — Real Production Network Inventory & Post-Deployment Audit

Updated: 2026-09-02 — Phase 05 Live Deployment Validation

## 1. Physical & Virtual Nodes

### Node 1: `ubuntu2` (Edge Proxy Gateway)
- **Public IP**: `144.33.17.173`
- **WireGuard IP**: `10.8.0.1` (`wg0`)
- **OS**: Linux 6.8.0-64-generic (Ubuntu 24.04 LTS x86_64)
- **Active Roles**:
  - Velocity Proxy 3.4.0-SNAPSHOT (`0.0.0.0:25565`)
  - BigBangHub Velocity Plugin `0.4.0` (active)
  - SimpleVoiceChat Proxy (`0.0.0.0:24454/udp`)
  - nLogin Authenticator & SkinsRestorer

### Node 2: `brainiac` (Dedicated Game Server Node)
- **WireGuard IP**: `10.8.0.2` (`wg0`)
- **Internal LAN**: `192.168.0.20`
- **OS**: Linux 6.8.0-60-generic (Ubuntu 24.04 LTS x86_64)
- **Active Paper Instances (all bound strictly to `10.8.0.2`)**:
  - `hubminigame`: Paper 1.21.4 (`10.8.0.2:25565`) — Role: `HUB` — BigBangHub Paper `0.4.0`
  - `bedwars`: Paper 1.21.4 (`10.8.0.2:25566`) — Role: `MINIGAME` (`bedwars`) — BigBangHub Paper `0.4.0`
  - `campominado`: Paper 1.21.4 (`10.8.0.2:25567`) — Role: `MINIGAME` (`campominado`) — BigBangHub Paper `0.4.0`
  - `hg`: Paper 1.21.4 (`10.8.0.2:25568`) — Role: `MINIGAME` (`hg`) — BigBangHub Paper `0.4.0`

---

## 2. Integration Health Matrix

| Server | Status | Registered Game | Match State | Players | Heartbeat Age |
|---|---|---|---|---|---|
| `hubminigame` | HEALTHY | N/A (Hub) | N/A | Variable | Hub Connection |
| `campominado` | HEALTHY | `campominado` | WAITING | 0/20 | < 1.0s |
| `bedwars` | HEALTHY | `bedwars` | WAITING | 0/20 | < 1.0s |
| `hg` | HEALTHY | `hg` | WAITING | 0/20 | < 1.0s |

---

## 3. Validated Capabilities Checklist

- [x] Modern Channel Protocol Registration (`bigbanghub:main`)
- [x] WireGuard Private Mesh Routing (latency avg: 9.1ms, loss: 0%)
- [x] Automatic Instance Discovery & Session Renewal
- [x] Dynamic Liveness Sweep & Ping Fallback
- [x] Solo Queue Matchmaking & Player Dispatch
- [x] Group Queue Atomic No-Split Dispatch
- [x] In-Flight Match Admission Verification & Consumption
- [x] 60-Second Reconnection Holding Window & `/reconnect` Return
- [x] Unscheduled Crash Evacuation & Safe Hub Fallback (`kill -9`)
- [x] Play Again & Rematch Flow Handling
- [x] Strict Network Boundary Security & Command Permissions
- [x] Rollback & Re-Deploy Verification
