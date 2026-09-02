# BigBangHub — Production Deployment Guide

## 1. Real Network Topology

BigBangHub operates across a hybrid multi-host topology connected via a secure WireGuard mesh:

| Host | Public IP / WireGuard IP | Role | Services & Ports |
|---|---|---|---|
| **ubuntu2** | `144.33.17.173` / `10.8.0.1` | Edge Proxy Gateway | Velocity Proxy (`0.0.0.0:25565`), SimpleVoiceChat (`24454/udp`) |
| **brainiac** | Private LAN / `10.8.0.2` | Dedicated Backend Node | Paper 1.21.4 servers bound to `10.8.0.2`: <br>• `hubminigame` (`25565`)<br>• `bedwars` (`25566`)<br>• `campominado` (`25567`)<br>• `hg` (`25568`) |

> **Security Guarantee**: All Paper backend instances bind strictly to `10.8.0.2` on the WireGuard VPN interface. External direct connections from the internet are physically impossible. Direct connections without Velocity/BungeeGuard authentication are rejected.

---

## 2. Startup and Shutdown Procedures

### Velocity (ubuntu2)
- **Startup**:
  ```bash
  tmux -S /tmp/tmux_shared new-session -d -s proxy "cd /home/ubuntu/proxy && sh ./startserver.sh"
  ```
- **Graceful Shutdown**:
  ```bash
  tmux -S /tmp/tmux_shared send-keys -t proxy 'shutdown' Enter
  ```

### Paper Backends (brainiac)
Each server runs in a dedicated tmux session under `/home/brainiac/bigbangcraft/`:
- **Hub (`hubminigame`)**:
  ```bash
  tmux -S /tmp/tmux_shared send-keys -t hubminigame 'stop' Enter
  ```
- **Minigames (`campominado`, `bedwars`, `hg`)**:
  ```bash
  tmux -S /tmp/tmux_shared send-keys -t campominado 'stop' Enter
  ```
Automatic start script loops ensure clean auto-restart upon graceful stop.

---

## 3. Operational Commands

### Proxy Console & Admin (`/bbhub`)
- `/bbhub status`: Displays general proxy status, registered games, instances, and queue depths.
- `/bbhub instances`: Displays real-time health, state, player count, heartbeat age, and active match IDs of runtime instances.
- `/bbhub matches`: Lists active matches, phase, participant counts, and duration.
- `/bbhub metrics`: Dumps internal diagnostic counters (routing attempts, transfers, admissions, registrations).
- `/bbhub reload`: Reloads plugin configurations dynamically without dropping players.

### Player Commands
- `/queue join <game>` / `/<game>`: Enters matchmaking for the designated minigame.
- `/queue leave`: Withdraws from current queue.
- `/party <create|invite|accept|leave|disband|list>`: Manages cross-server parties with atomic queue dispatch.
- `/reconnect`: Reconnects to an ongoing match within the 60s reconnect window.
- `/playagain`: Requeues for the same game from within the match or upon game conclusion.

---

## 4. Rollback Procedure

Backups are archived before every update in:
- `ubuntu2:/home/ubuntu/backups_pre_bigbanghub_*`
- `brainiac:/home/brainiac/backups_pre_bigbanghub_*`

To rollback an instance:
1. Stop the target server: `tmux send-keys -t <server> 'stop' Enter`.
2. Replace jar/config from the backup archive:
   ```bash
   cp /home/brainiac/backups_pre_bigbanghub_20260902_163957/minigames/campominado/plugins/bigbanghub-paper-0.4.0.jar.bak plugins/
   ```
3. Allow server to restart and verify via `/bbhub instances`.

---

## 5. Troubleshooting & Root Cause Resolutions

During Phase 05 live deployment, key edge cases were identified in production and engineered into the codebase:

1. **Backend Rate Limiting Bursts**:
   - *Symptom*: Rapid sequential packets (`INSTANCE_REGISTER` followed by `ADMISSION_REQUEST`) were dropped by strict time-delta checks.
   - *Fix*: Replaced rigid 20ms delta with a thread-safe `TokenBucket` allowing controlled bursts up to 50 packets while maintaining a sustained 50 pkt/s limit.

2. **Idle Instance Registration Guard**:
   - *Symptom*: Re-registration of idle instances (`REPLACED`) inadvertently triggered match crash reconciliation.
   - *Fix*: Guarded crash reconciliation to only trigger when an active match was bound to that instance (`findActiveForInstance().isPresent()`).

3. **Paper Channel Handshake Timing**:
   - *Symptom*: Packets dispatched during `PlayerJoinEvent` were dropped before client/proxy plugin channel negotiation completed.
   - *Fix*: Scheduled admission requests on next tick (`20L` delay) after player fully transitions into `PLAY` state and channels are active.

4. **Matchmaking Ticket Generation**:
   - *Symptom*: Fresh matches allocated on idle instances were routed without pre-generated tickets.
   - *Fix*: Velocity `dispatchQueue` now always issues admission tickets with `effectiveMatchId`, and `InMemoryMatchRegistry.admitPlayer` automatically establishes the match session if not previously pre-registered.
