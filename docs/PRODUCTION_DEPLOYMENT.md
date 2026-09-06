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
- **Como roda**: loop em `startserver.sh` (sem tmux — `tmux ls` não mostra o proxy).
- **Restart** (derruba jogadores por ~15s):
  ```bash
  PID=$(pgrep -o -f 'java.*velocity\.jar'); kill $PID
  # o loop do startserver.sh sobe de novo sozinho em ~10s
  tail -n 5 ~/proxy/logs/latest.log  # Loaded plugin bigbanghub X.Y.Z
  ```
- **Nunca** `pkill -f velocity.jar` direto no mesmo comando SSH (mata o próprio SSH).

### Paper Backends (brainiac)
Each server runs in a dedicated tmux session under `/home/brainiac/bigbangcraft/`
(`hubminigame`, `bedwars`, `campominado`, `hg`), cada um com loop em
`startserver.sh`: enviar `stop` reinicia sozinho em ~15s.
- **Hub (`hubminigame`)**:
  ```bash
  tmux -S /tmp/tmux_shared send-keys -t hubminigame 'stop' Enter
  ```
- **Minigames (`campominado`, `bedwars`, `hg`)**:
  ```bash
  tmux -S /tmp/tmux_shared send-keys -t campominado 'stop' Enter
  ```
Reinicie de forma escalonada (um por vez) para não derrubar a rede inteira.

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
- `/queue leave`: Withdraws from current queue (queue only, not match).
- `/leave` (aliases `/hub`, `/lobby`, `/sair`): Abandons the current match and returns to Hub (0.4.3+).
- `/party <create|invite|accept|leave|disband|list>`: Manages cross-server parties with atomic queue dispatch.
- `/reconnect`: Reconnects to a `DISCONNECTED` match within the 60s window (crash recovery only; voluntary leaves abandon since 0.4.4).
- `/playagain`: Requeues for the same game from within the match or upon game conclusion.

---

## 4. Rollback Procedure

Backups are archived before every update in:
- `ubuntu2:~/backup/bigbanghub_<versão>_<data>/` (jars + `config.yml` + `velocity.toml`)
- `brainiac:~/backups/bigbanghub_<versão>_<data>/` (4 jars + hub `config.yml` + `npcs.yml`)

To rollback an instance (example 0.4.4 → 0.4.3):
1. Copy the previous jar from its backup dir over `plugins/`, removing the newer jar (never two version jars together).
2. Restart that component only (`stop` via tmux for Paper; `kill <PID>` for Velocity).
3. Verify via `/bbhub instances` and `Loaded plugin bigbanghub X.Y.Z` in logs.
4. NPC `npcs.yml`: remember FancyNpcs saves on shutdown — restore + `fancynpcs reload` without restart (see `docs/deployment/ROLLBACK.md`).

Legacy example (pre-0.4.0 baseline):
```bash
cp /home/brainiac/backups_pre_bigbanghub_20260902_163957/minigames/campominado/plugins/bigbanghub-paper-0.4.0.jar.bak plugins/
```

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

5. **NPC Direct Transfer Bypass (0.4.1)**:
   - *Symptom*: FancyNpcs `send_to_server` skipped queue→ticket; players bounced with "no active admission ticket".
   - *Fix*: NPC actions use `player_command <game>`; aliases registered as Brigadier commands; `server.connect` permission defaults to `op`.
   - *Ops lesson*: FancyNpcs rewrites `npcs.yml` on shutdown — always edit + `fancynpcs reload` without restarting, or the fix reverts.

6. **Stuck ACTIVE vs No-Reconnect Contradiction (0.4.3)**:
   - *Symptom*: After `/server hub`, re-queue blocked ("já possui partida") while `/reconnect` found nothing.
   - *Fix*: Paper sends `DISCONNECTED` with fallback carrier; Velocity reconciles on `ServerPostConnect`; stale `DISCONNECTED` auto-abandons on re-queue; `/leave|/hub|/lobby|/sair` abandons explicitly.

7. **Hub-Return Yank Loop (0.4.4)**:
   - *Symptom*: Every `/hub` yanked the player back to the minigame via auto-reconnect.
   - *Fix*: Hub-bound `ServerPreConnect` abandons the match before transferring (fresh logins exempted for crash recovery).

8. **Proxy Reachability of campominado/hg**:
   - `velocity.toml` points them at `127.0.0.1:25567/25568` via SSH tunnel `start_tunnel.sh` (`-L` to `10.8.0.2`), not directly at `10.8.0.2`. If those games are unreachable, check the tunnel first (`ss -ltn | grep 2556` on ubuntu2).
