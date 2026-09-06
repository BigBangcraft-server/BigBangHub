# BigBangHub — Operational Runbook

## 1. Emergency Procedures

### Scenario A: Backend Instance Unresponsive / Hang
1. Identify the stuck process on `brainiac`:
   ```bash
   ssh brainiac "ss -tulpn | grep -E '25565|25566|25567|25568'"
   ```
2. Force kill the hung process:
   ```bash
   ssh brainiac "kill -9 <PID>"
   ```
3. Velocity automatically transitions the instance:
   - `HEALTHY` -> `SUSPECT` (5s) -> `UNAVAILABLE` (10s).
   - In-flight players are safely evacuated back to `hubminigame`.
   - The instance's start script automatically relaunches Paper.
   - Once online, Paper sends `INSTANCE_REGISTER` and returns to `HEALTHY` pool.

### Scenario B: Velocity Proxy Crash / Restart
1. Restart proxy (no tmux — loop `startserver.sh` restarts automatically):
   ```bash
   ssh ubuntu2 "PID=$(pgrep -o -f 'java.*velocity\.jar'); kill $PID"
   ```
   Never `pkill -f velocity.jar` inside the same SSH command (it kills your own SSH).
2. Upon proxy boot, backends re-register on next heartbeat (≤3s); campominado/hg re-appear via `Discovered and registered` (tunnel must be up).
3. Verify cluster state via console:
   ```text
   bbhub instances
   ```

---

## 2. Provisioning a New Minigame Server

1. **Configure Paper Instance**:
   - Assign a new dedicated port on `brainiac` (e.g. `25569`).
   - In `server.properties`:
     ```properties
     server-ip=10.8.0.2
     server-port=25569
     online-mode=false
     ```
   - Copy `bigbanghub-paper-0.4.4.jar` into `plugins/` (remove any older version jar).
   - In `plugins/BigBangHub/config.yml`:
     ```yaml
     server:
       role: MINIGAME
       instance:
         instance-id: novominigame
         game-id: novominigame
         server-name: novominigame
         heartbeat:
           interval: 3s
         capacity:
           min-players: 2
           max-players: 10
         accepting-players: true
     ```

2. **Register in Velocity**:
   - In `ubuntu2:/home/ubuntu/proxy/velocity.toml`:
     ```toml
     [servers]
     novominigame = "10.8.0.2:25569"
     ```
     (campominado/hg are the exception: they go through the SSH tunnel as `127.0.0.1:25567/25568` — see PRODUCTION_DEPLOYMENT §5.8.)
   - In `ubuntu2:/home/ubuntu/proxy/plugins/bigbanghub/` add the game to `games.yml`, the server to `servers.yml`, the alias to `config.yml`, and allowlist in `registry.allowed`.
   - Add the same alias to the Hub Paper `config.yml` (`hubminigame/plugins/BigBangHub/config.yml`).
   - If the game gets an NPC: `player_command <alias>`, then `fancynpcs reload` (no restart).
   - Run `/bbhub reload` in Velocity console (config-only; new jar still needs restart).

---

## 3. Handling State Desynchronization

If an instance becomes out-of-sync with proxy match registry:
1. Run `/bbhub instances` to inspect the instance status.
2. If reservations are orphaned:
   ```text
   bbhub reload
   ```
   Sweep task automatically purges expired reservations and orphaned tickets.
3. If necessary, execute `/bbhub match <id> abort` to cancel an orphaned match and safely route participants back to Hub.

---

## 4. Stuck Player ("já possui partida" / yank loop)

1. Ask the player to run `/leave` (or `/hub`): abandons the match and returns to Hub (0.4.3+).
2. If the player cannot run commands, abort their match (0.4.4 keeps them on Hub afterwards):
   ```text
   /bbhub matches
   /bbhub match <id> abort
   ```
3. If NPCs bounce players with "no active admission ticket": the NPC uses `send_to_server` — fix to `player_command` + `fancynpcs reload` (0.4.1+, see MINIGAME_INTEGRATION §5).
4. If it's a dev/maintenance backend with no integrated minigame yet: put it in
   maintenance mode instead of fighting matches (`auto-create-match: false` +
   `accepting-players: false`, game queue off) — see OPERATIONS §5.

---

## 5. Continuous Health Monitoring

Run health check on proxy console:
```text
bbhub status
bbhub metrics
```
Healthy baseline indicators:
- `Transfers Failed: 0`
- `Return Failures: 0`
- `Heartbeats: Active & age < 2.0s`
- `Instances: HEALTHY WAITING`
