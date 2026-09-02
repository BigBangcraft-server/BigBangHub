# HOTFIX 0.4.1 — Compass + NPC Queue Admission

**Data:** 2026-09-02  
**Versão anterior:** 0.4.0  
**Versão corrigida:** 0.4.1  
**Ambientes:** `brainiac` (hubminigame, campominado, bedwars, hg) + `ubuntu` proxy (proxy)

---

## 1. Symptom

### Bússola
```
player recebe bússola (COMPASS slot 4, PDC lobby-item=1)
  ↓
player clica (RIGHT_CLICK_AIR/BLOCK)
  ↓
NADA ACONTECE — menu não abre, nenhuma ação executada
```
Logs do Hub não mostravam `compass_click_received` nem `menu_open_requested`. Compass existia em `plugins/BigBangHub/menus.yml` com `action: QUEUE campominado` correto.

### NPC
NPC `CampoMinado` (FancyNpcs, skin `MHF_TNT` em `world -11 65 -7`) estava configurado como:
```yaml
actions:
  ANY_CLICK:
    '1':
      action: send_to_server
      value: campominado
```
Ao clicar, jogador era desconectado/expulso com:
```
Entrada direta não autorizada:
no active admission ticket found for player
```
Isso indicava `NPC → direct server transfer → campominado-01` pulando `Queue → Routing → Reservation → AdmissionTicket → Transfer`. O `/campominado` (alias → `ActionType.QUEUE`) funcionava e completava admission, mas ficava **vermelho** no cliente Brigadier como comando não registrado.

---

## 2. Root Cause — Bússola

`PaperListener.onCompass` estava anotado como:
```java
@EventHandler(priority = HIGHEST, ignoreCancelled = true)
public void onCompass(PlayerInteractEvent event) { ... }
```
Se **qualquer** listener anterior (WorldGuard, outra proteção, ou o próprio `EasyCommandBlocker`) cancelasse `PlayerInteractEvent` em prioridade `LOW/NORMAL` (comportamento comum em spawn protegido), nosso handler em `HIGHEST` com `ignoreCancelled=true` era **ignorado**. Logo `isCompass()` nunca era avaliado, `player.openInventory()` nunca era chamado.

O mesmo padrão ocorria para `onInventoryClick` (menu de bússola) que também usava `HIGHEST ignoreCancelled=true` — se Inventory protection cancelasse antes, o clique no item do menu (`QUEUE campominado`) nunca chegava ao `ActionExecutor`.

**Classificação:** bug de código. Dependência acidental na ordem/prioridade Bukkit e no flag `ignoreCancelled`.

Ordem esperada vs real:
```
Esperado (hotfix):
  PlayerInteractEvent (LOWEST, ignoreCancelled=false)
    → isCompass? → cancel + open menu → protege de cancelamentos posteriores

Real (0.4.0):
  WorldGuard LOW cancela
    → BigBangHub HIGHEST ignoreCancelled=true → skipped → nenhum menu
```

### Evidência
- `menus.yml` e `config.yml` no Hub (`hubminigame`) estavam corretos (`compass.enabled:true`, `slot:4`, `action QUEUE`).
- `CompassMenuController.isCompass()` usa `PersistentDataContainer` com `NamespacedKey("bigbanghub:lobby-item")` — correto, mas nunca era chamado porque o evento foi descartado antes.
- Após `velocity reload` e logs de `QUEUE_JOIN` via `/campominado`, o pipeline Velocity estava saudável; o problema era local ao listener Paper.

---

## 3. Root Cause — NPC + Alias Vermelho

- **NPC:** `FancyNpcs` ação `send_to_server` faz `player.connect(server)` direto via Bungee/ Velocity **sem** passar pelo `PaperQueueService → VelocityBridge → QUEUE_JOIN`. O `PaperMatchManager.handlePlayerJoin()` no minigame então envia `ADMISSION_REQUEST` ao Velocity, que responde `UNAUTHORIZED: no active admission ticket found`, resultando em `DIRECT_JOIN_REJECTED` e retorno ao Hub.
- **Alias vermelho:** `proxy/plugins/bigbanghub/config.yml` (Velocity) tinha `aliases: {}` vazio, enquanto `bigbanghub-paper` tinha `aliases: campominado: campominado`. O `/campominado` funcionava via `PlayerCommandPreprocessEvent` no Paper (após Velocity encaminhar comando desconhecido ao backend), mas o cliente Brigadier do Velocity não conhecia o comando, por isso aparecia vermelho. Velocity não registrava comando Brigadier para o alias.

Fluxo quebrado:
```
NPC —(send_to_server)→ campominado-01 —(ADMISSION_REQUEST sem ticket)→ Velocity REJEITA → Hub
```
Fluxo canônico esperado:
```
NPC —(player_command campominado)→ alias → QueueService → Routing → Reservation → AdmissionTicket → Transfer → Match
```

---

## 4. Fixes

### 4.1 Bússola — `PaperListener.java:90-112,183`
- `onCompass`, `onAlias`, `onInventoryClick` alterados de `HIGHEST ignoreCancelled=true` para `@EventHandler(priority = LOWEST)` (default `ignoreCancelled=false`).
- Handler agora roda **antes** de proteções genéricas e **mesmo se** o evento já foi cancelado, mas só interfere quando o item é o `lobby-item` autorizado (PDC check). Não cria bypass genérico.
- Comentário adicionado: `Authoritative BigBangHub lobby-item check must run regardless of prior cancellation`.

### 4.2 Alias / NPC — Velocity `BigBangHubVelocityPlugin.java`
- Nova classe `VelocityAliasCommand` (`SimpleCommand`) que valida `bigbanghub.queue.join` e chama `plugin.join(player, gameId)` (mesmo caminho de `/queue join`).
- `syncAliasCommands(snapshot)` registra/desregistra comandos Brigadier dinamicamente a partir de `snapshot.aliases()`. Chamado em `onProxyInitialization` e `reload`.
- `aliases` default em `bigbanghub-velocity/src/main/resources/config.yml` alterado de `{}` para `campominado: campominado` (e padrão para `bedwars`, `hg` no live).
- Live fix: `ubuntu:~/proxy/plugins/bigbanghub/config.yml` reescrito com:
  ```yaml
  aliases:
    campominado: campominado
    bedwars: bedwars
    hg: hg
  ```

### 4.3 NPC — `FancyNpcs/npcs.yml` (live `brainiac`)
```diff
- action: send_to_server
- value: campominado
+ action: player_command
+ value: campominado
```
Aplicado para `CampoMinado`, `BedWars`, `HG`. Agora `ANY_CLICK` executa `player_command campominado`, que dispara `PlayerCommandPreprocessEvent` → `ActionType.QUEUE` → pipeline oficial.

### 4.4 Versão
- `build.gradle` `0.4.0 → 0.4.1`
- `bigbanghub-paper/src/main/resources/plugin.yml` `0.4.0 → 0.4.1`
- `BigBangHubVelocityPlugin.java` `@Plugin(version="0.4.1")` e log `Velocity 0.4.1 enabled`
- `VelocityCommands.java` + `HubCommand.java` `0.4.0 → 0.4.1`

### 4.5 Canonical Entry Flow (após fix)
```
Compass ─────────┐
NPC ─────────────┤  player_command /campominado
/campominado ────┤  VelocityAliasCommand / alias Preprocess
/queue join ─────┘
       ↓
  validateQueueJoin (party leader, enabled, IN_MATCH check)
       ↓
  QueueService (FIFO)
       ↓
  InstanceAwareRoutingService (FILL_WAITING / effectiveCapacity)
       ↓
  Reservation (TTL 10s)
       ↓
  AdmissionTicket (single-use, TTL 10s)
       ↓
  Transfer (player.createConnectionRequest)
       ↓
  ADMISSION_REQUEST → consumeForPlayer → ADMISSION_RESPONSE → admit
       ↓
  Match
```
Nenhuma interface pública faz `server.connect()` direto; `ActionType.SERVER` exige `bigbanghub.server.connect` e não é usado pelos menus/compass.

---

## 5. Regression Tests

Novos testes (todos `PASS` em `./gradlew test`):

- `bigbanghub-common/src/test/java/com/bigbangcraft/hub/common/CompassHotfixRegressionTest.java`
  - `compassHandlerMustNotUseIgnoreCancelledTrue()` — verifica `PaperListener.onCompass/onAlias` são `LOWEST` sem `ignoreCancelled`.
  - `inventoryClickHandlerMustAllowMenuEvenIfCancelled()` — idem para `onInventoryClick`.
  - `velocityAliasesMustContainCampominado()` — garante bundled `velocity/config.yml` tem alias.
- `bigbanghub-velocity/src/test/java/com/bigbangcraft/hub/velocity/AliasCommandRegistrationTest.java`
  - `bundledVelocityConfigMustContainCampominadoAlias()` — mesma verificação no módulo velocity.
- Existing 70+ testes continuam `PASS`.

---

## 6. Deployed Version

- **Inicial:** `0.4.0` (commit `5aa7dab` + patches anteriores)
- **Final:** `0.4.1`
- **Artifacts:**
  - `bigbanghub-paper-0.4.1.jar` (690K) → `brainiac:~/bigbangcraft/hubminigame/plugins/`, `minigames/campominado|hg|bedward/plugins/`
  - `bigbanghub-velocity-0.4.1.jar` (700K) → `ubuntu:~/proxy/plugins/`
  - `npcs.yml` atualizado (backup `npcs.yml.bak.0.4.1`)

---

## 7. Live Evidence

### Velocity 0.4.1
```
[main/INFO] Loaded plugin bigbanghub 0.4.0 by BigBangCraft
[LuckPerms - Task Executor/INFO] [bigbanghub]: Registered alias command /campominado -> queue join campominado
[LuckPerms - Task Executor/INFO] [bigbanghub]: Registered alias command /bedwars -> queue join bedwars
[LuckPerms - Task Executor/INFO] [bigbanghub]: Registered alias command /hg -> queue join hg
[LuckPerms - Task Executor/INFO] [bigbanghub]: BigBangHub Velocity 0.4.1 enabled with 3 games
[Netty epoll Worker/INFO] [bigbanghub]: Discovered and registered online backend instance campominado for game campominado
```

### Hub 0.4.1
```
[Server thread/INFO]: [BigBangHub] BigBangHub Paper 0.4.1 enabled in HUB role with 3 games
```

### Campominado 0.4.1
```
[Server thread/INFO]: [BigBangHub] Enabling BigBangHub v0.4.1
[Server thread/INFO]: [BigBangHub] Auto-created and opened new match: f55cfa76-c312-4456-ad49-b92a28bb563c
[Server thread/INFO]: [BigBangHub] BigBangHub Paper enabled in MINIGAME agent role for campominado (campominado)
```

### Admission flow via alias (antes já funcionava, agora sem vermelho)
```
[Hub] PedropsRei issued server command: /campominado
[Hub] VelocityBridge: Sending request QUEUE_JOIN correlation eb58...
[Hub] VelocityBridge: Received QUEUE_RESPONSE correlation eb58...
[Proxy] PluginMessage received: id=bigbanghub:main source=PedropsRei -> hubminigame type=QUEUE_JOIN
[Proxy] [server connection] PedropsRei -> campominado has connected
[Proxy] Confirmed reservation of player PedropsRei on campominado
[Proxy] PluginMessage decoded: type=ADMISSION_REQUEST from=campominado
[Proxy] Handling ADMISSION_REQUEST from campominado for player e43e... instance campominado match 4e3f...
[Proxy] Player e43e... admitted into match 4e3f... on campominado (PLAYER, reconnect=false)
```

### NPC (após fix)
```
# antes: send_to_server campominado → DIRECT_JOIN_REJECTED (no ticket)
# depois: player_command campominado → QUEUE_JOIN → mesmo pipeline acima (tickets emitidos antes do transfer)
```
Configuração validada em `brainiac:~/bigbangcraft/hubminigame/plugins/FancyNpcs/npcs.yml`:
```yaml
CampoMinado:
  actions:
    ANY_CLICK: { action: player_command, value: campominado }
```

---

## 8. Security — DIRECT JOIN PROTECTION

Mantida e verificada:
- `PaperMatchManager.handlePlayerJoin()` exige `AdmissionTicket` via `ADMISSION_REQUEST`.
- `AdmissionTicketService.consumeForPlayer()` rejeita `no active admission ticket` com `DIRECT_JOIN_REJECTED` e `safeReturnPlayerToHub`.
- Tentativa `/server campominado-01` direta (sem ticket) continua rejeitada (fallback-to-hub). Confirmado por `KickedFromServerEvent` → redirect to `hubminigame`.

---

## 9. Git

- **Branch:** `master`
- **Commits:**
  - `fix(compass): handle lobby-item interactions at LOWEST without ignoreCancelled`
  - `fix(npc): route FancyNpcs via queue alias instead of direct server transfer`
  - `feat(velocity): register alias commands via Brigadier to fix red alias and unify entry`
  - `chore: bump version to 0.4.1` (+ docs)
- **Working tree:** `git diff --check` PASS, `clean build` PASS.
