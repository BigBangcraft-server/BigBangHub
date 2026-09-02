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
1. Restart proxy:
   ```bash
   ssh ubuntu2 "cd /home/ubuntu/proxy && tmux -S /tmp/tmux_shared new-session -d -s proxy 'sh ./startserver.sh'"
   ```
2. Upon proxy boot, all Paper backends re-register automatically on their next heartbeat pulse (within 3 seconds).
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
   - Copy `bigbanghub-paper-0.4.0.jar` into `plugins/`.
   - In `plugins/BigBangHub/config.yml`:
     ```yaml
     server-id: "novominigame"
     role: "MINIGAME"
     minigame:
       game: "novominigame"
       autostart: true
     ```

2. **Register in Velocity**:
   - In `ubuntu2:/home/ubuntu/proxy/velocity.toml`:
     ```toml
     [servers]
     novominigame = "10.8.0.2:25569"
     ```
   - In `ubuntu2:/home/ubuntu/proxy/plugins/BigBangHub/config.yml` (and `servers.yml` / `games.yml`):
     Add game definition and default matchmaking strategy.
   - Run `/bbhub reload` in Velocity console.

---

## 3. Handling State Desynchronization

If an instance becomes out-of-sync with proxy match registry:
1. Run `/bbhub instances` to inspect the instance status.
2. If reservations are orphaned:
   ```text
   bbhub reload
   ```
   Sweep task automatically purges expired reservations and orphaned tickets.
3. If necessary, execute `/bbhub abort <matchId>` to cancel an orphaned match and safely route participants back to Hub.

---

## 4. Continuous Health Monitoring

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
