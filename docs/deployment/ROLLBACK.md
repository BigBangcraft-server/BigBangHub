# BigBangCraft Rollback & Disaster Recovery Procedures

This document provides exact operational procedures to revert BigBangHub deployment to the pre-0.4.0 baseline across the BigBangCraft network.

---

## 1. Backup Locations & Baselines

### 1.1. Proxy Node (`ubuntu2`)
* **Backup Directory**: `/home/ubuntu/backups/bigbanghub-pre-0.4-live/`
* **Backed up assets**:
  * `/home/ubuntu/proxy/velocity.toml`
  * `/home/ubuntu/proxy/plugins/` (full directory tree)
  * Plugin SHA256 hashes (`plugins_sha256.txt`):
    * `AdvancedServerList-Velocity-5.8.0.jar`: `1ab117a54f8ba3ff56ae89b5613c317b8275b9caeb8e5f798599a31501725541`
    * `LuckPerms-Velocity.jar`: `f587a9fbe14a1fc327c2b53dcb388aed64c920fd8186c85db008ee18e80f033c`
    * `SkinsRestorer.jar`: `56fed7d9fa5862356851307cdb20707adb5d43f0dd6451a0225ebbd03e8d04a0`
    * `hub.jar`: `c16621a87622d7e558fad7d91bba8ce18f00db7c081ebfc02ec33dcbd791c9e4`
    * `nLogin.jar`: `591a0058862664751a261305963f2eca34d2b63e82272bb8d84e0d2af53cec59`
    * `voicechat-velocity-2.6.18.jar`: `b03cebc45c1583edf8c7456fa67f0a6248eb95a856249378173cc1ea8b8d0d68`

### 1.2. Backend Node (`brainiac`)
* **Backup Directory**: `/home/brainiac/bigbangcraft/backups/bigbanghub-pre-0.4-live/`
* **Backed up assets**:
  * `hubminigame/`:
    * Full plugins directory (`plugins/`)
    * `paper-global.yml`, `server.properties`, `config/`
    * Pre-existing plugins and hashes:
      * `BungeeGuard.jar`: `e6ac7ca664a7e749a274314cab259cf8dc909a904af2e3dfed7e9cd351fabecd`
      * `EasyCommandBlocker-1.18.1.jar`: `b6aca60ce9a743d9358d979f8c5e901f8d92488804189bc1e372965a8752bfbd`
      * `FancyHolograms-2.11.0+193.jar`: `6ca347199ef28939a14c27b1eb0d95241e42a09318ef39d33d33d9ce71b3a0b6`
      * `FancyNpcs-2.11.0+370.jar`: `8f29507e5cb1b765ffc8db2d40aea287e57d026ad6b095c1dbc6a51444498eee`
      * `FastAsyncWorldEdit-Paper-2.15.4.jar`: `bbb1875e823ca01591bd23427e86f490e17cd82fe769dcb7e82a955dd8371acc`
      * `HubMinigame.jar`: `727c38161cf234c3adb35b2d068faafe56f0a233e225888d408aec316bf5bdcb`
      * `LuckPerms-Bukkit-5.5.9.jar`: `f8bb3065ad5a1d1395f18750e496dd00237f034c5dd9cc86889a70cfd7585467`
      * `nLogin-2.0.19.jar`: `591a0058862664751a261305963f2eca34d2b63e82272bb8d84e0d2af53cec59`
      * `Vault.jar`: `a6b5ed97f43a5cf5bbaf00a7c8cd23c5afc9bd003f849875af8b36e6cf77d01d`
      * `VoidGen-2.3.8.jar`: `fa4d882f0923ad1c2bb25b4bca33920076976bfe08f9be0c02bc8929302d92cc`
      * `worldguard-bukkit-7.0.18.jar`: `08f3ef58bc521c635d8c78aedaca96f151d2d397e7cd8018584955dd7468eb05`
  * `campominado/`:
    * Full plugins directory (`plugins/`)
    * `paper-global.yml`, `server.properties`, `config/`
    * Pre-existing plugins and hashes:
      * `BigBangMinefield-1.0.0.jar`: `d1efd1b87717c5e6784e93acd951264af89a578f4177a24ee3434a7630324e71`
      * `BungeeGuard.jar`: `e6ac7ca664a7e749a274314cab259cf8dc909a904af2e3dfed7e9cd351fabecd`
      * `EasyCommandBlocker-1.18.1.jar`: `b6aca60ce9a743d9358d979f8c5e901f8d92488804189bc1e372965a8752bfbd`
      * `worldedit-bukkit-7.4.5.jar`: `e5696a6d064b9969437a8888be91b0941148a28e0c3736de1554a00254a5d142`

---

## 2. Step-by-Step Rollback Execution

### 2.1. Velocity Rollback (`ubuntu2`)
Execute via SSH on `ubuntu2`:
```bash
# 1. Stop Velocity proxy
tmux -S /tmp/tmux_shared send-keys -t proxy "end" Enter

# 2. Wait 3 seconds for process termination
sleep 3

# 3. Remove BigBangHub plugin and data folder
rm -f /home/ubuntu/proxy/plugins/bigbanghub-velocity*.jar
rm -rf /home/ubuntu/proxy/plugins/bigbanghub

# 4. Restore previous plugins and configuration from backup
cp -a /home/ubuntu/backups/bigbanghub-pre-0.4-live/velocity.toml /home/ubuntu/proxy/velocity.toml

# (If startserver.sh loop didn't auto-restart, launch via start_tmux.sh)
/home/ubuntu/proxy/start_tmux.sh
```

### 2.2. Hub Rollback (`hubminigame` on `brainiac`)
Execute via SSH on `brainiac`:
```bash
# 1. Stop Hub server
tmux -S /tmp/tmux_shared send-keys -t hubminigame "stop" Enter

# 2. Wait 5 seconds for Paper to cleanly finish save-all and shut down
sleep 5

# 3. Remove BigBangHub Paper plugin and configuration
rm -f /home/brainiac/bigbangcraft/hubminigame/plugins/bigbanghub-paper*.jar
rm -rf /home/brainiac/bigbangcraft/hubminigame/plugins/BigBangHub

# 4. Ensure original HubMinigame.jar is restored in plugins
cp -a /home/brainiac/bigbangcraft/backups/bigbanghub-pre-0.4-live/hubminigame/plugins/HubMinigame.jar /home/brainiac/bigbangcraft/hubminigame/plugins/

# (startserver.sh loop restarts the server automatically)
```

### 2.3. Minigame Rollback (`campominado` on `brainiac`)
Execute via SSH on `brainiac`:
```bash
# 1. Stop campominado server
tmux -S /tmp/tmux_shared send-keys -t campominado "stop" Enter

# 2. Wait 5 seconds
sleep 5

# 3. Remove BigBangHub Paper plugin and configuration
rm -f /home/brainiac/bigbangcraft/minigames/campominado/plugins/bigbanghub-paper*.jar
rm -rf /home/brainiac/bigbangcraft/minigames/campominado/plugins/BigBangHub

# (startserver.sh loop restarts the server automatically)
```

---

## 3. Rollback Verification Matrix

| Component | Target File | Verification Check |
|---|---|---|
| Velocity | `/home/ubuntu/proxy/plugins/` | Only original jars present, latest.log shows clean boot |
| Hub | `/home/brainiac/bigbangcraft/hubminigame/plugins/` | `HubMinigame.jar` active, no BigBangHub jar |
| Campo Minado | `/home/brainiac/bigbangcraft/minigames/campominado/plugins/` | `BigBangMinefield-1.0.0.jar` active, no BigBangHub jar |
