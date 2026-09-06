# HOTFIX 0.4.3 — Stuck Match on Server Switch (Complete Fix)
## Addendum 0.4.4 — Hub-Return Yank Loop (abaixo)

**Data:** 2026-09-03
**Versão anterior:** 0.4.2
**Versão corrigida:** 0.4.3

## 1. Sintoma (Problema 3)

Após entrar via fila com sucesso e dar `/lobby` (`/server hubminigame`):
- `/server campominado` -> rejeitado (correto, sem ticket)
- `/reconnect` -> "sem partida em andamento"
- `/campominado` ou `/queue join` -> "já possui partida ativa"
- `/queue leave` -> "não está em fila"

Contradição: preso entre "já em partida" e "sem partida", `Queued 0` mas bloqueado.

## 2. Causa raiz

1. `PaperMatchManager.handlePlayerQuit` usava `bridge.sendAny` (0.4.1) depois `bridge.send(playerId)` (0.4.2).
   Ambos usam conexão do player saindo, que está fechando durante `/server` switch.
   Último player sem carrier = mensagem `DISCONNECTED` perdida. Velocity nunca aprende,
   registry fica `ACTIVE` para sempre (só `DISCONNECTED` expira via sweeper).
2. Velocity não tinha reconciliação em `ServerPostConnect`: só `DisconnectEvent` (proxy quit)
   marcava `DISCONNECTED`. Switch hub<->minigame dependia 100% da mensagem backend.
3. `validateQueueJoin` bloqueia re-queue mesmo quando `DISCONNECTED` (slot reconnect 60s).
   Pós-`/lobby` voluntário, player esperava rejogar imediatamente, mas ficava 60s preso
   ou para sempre se mensagem perdida.
4. Sem comando `/leave` para abandonar explicitamente. `/queue leave` só sai da fila,
   não da partida.

## 3. Fixes (0.4.3)

### Velocity `BigBangHubVelocityPlugin.java`
- `reconcileServerSwitch(player, previous, current)` chamado em `onServerPostConnect`:
  se registry ainda `ACTIVE` mas player já não está na instância da partida,
  marca `DISCONNECTED` autoritativamente (ou remove se `reconnect-timeout=0`).
  Cobre perda total de mensagem backend.
- `tryAbandonStaleMatchForRequeue(uuid)`: se `DISCONNECTED`, remove + limpa
  ticket/reserva/fila/rematch e permite novo `join` imediato com aviso
  "partida anterior abandonada". `ACTIVE` continua bloqueando (deve `/leave`).
- `abandonMatch(uuid, reason)` + `leaveMatch(player)`: remove `ACTIVE` ou `DISCONNECTED`,
  limpa ticket/reserva/fila/party `IN_MATCH/ASSIGNED`, transfere ao hub.
  Nunca deixa contradição.
- `join()` e `handleQueueJoin()` chamam `tryAbandon` antes de validar.
  Party: membros `DISCONNECTED` são abandonados automaticamente; `ACTIVE` bloqueia.
- `syncAliasCommands`: re-registra quando mapping muda (antes `continue` ignorava).
  Adicionado `aliasTargets` map.
- Novo `/leave` aliases `/hub /lobby /sair` via `VelocityLeaveCommand` (`bigbanghub.match.leave`).
- `handleServerConnect`: exige `bigbanghub.server.connect`, senão rejeita. Fecha bypass
  direto via plugin message.
- Bundled `config.yml`: `aliases` agora `campominado, bedwars, hg` (antes só campominado).

### Paper
- `VelocityBridge.sendWithFallback(uuid, ...)`: tenta conexão direta, fallback para
  qualquer outro online como carrier. Loga quando sem carrier (Velocity reconcilia).
- `PaperMatchManager.handlePlayerQuit`: usa `sendWithFallback` + idempotente
  (segunda chamada Kick+Quit é no-op se já `DISCONNECTED`).
- `PaperInstanceListener`: mantém `Quit`+`Kick` (seguro com idempotência).
- `ActionExecutor.joinQueue`: exige `bigbanghub.queue.join`.
- `plugin.yml`: `bigbanghub.server.connect` default `true -> op` (nenhum menu público
  deve fazer `SERVER` direto; canônico é `QUEUE`). Adicionado `bigbanghub.match.leave` default true.
- Versão `0.4.2 -> 0.4.3` em `build.gradle`, `plugin.yml`, `HubCommand`, `VelocityCommands`, `@Plugin`.

## 4. Fluxo pós-fix

```
Match ACTIVE em campominado-01
  /server hub /lobby /hub /leave
    Paper Quit/Kick -> setDisconnected local + sendWithFallback DISCONNECTED
    Velocity PostConnect(hub, prev=campominado-01)
      reconcile: ACTIVE mas now!=instance -> DISCONNECTED (se mensagem perdida)
      checkAndHandleReconnect -> /reconnect funciona
    /campominado -> tryAbandon DISCONNECTED -> JOINED (avisa abandonada)
    /leave -> abandon ACTIVE/DISCONNECTED + transfer hub
    /server campominado direto -> sem ticket -> DIRECT_JOIN_REJECTED (mantido)
    /reconnect sem DISCONNECTED -> "sem partida" (correto)
    /queue leave sem fila -> "não está em fila" mas match já tratável via /leave
```

## 5. Testes

- `StuckMatchFixRegressionTest` (7 testes, PASS):
  bundled 3 aliases, reconcile ACTIVE->DISCONNECTED, requeue abandona DISCONNECTED,
  ACTIVE ainda bloqueia, leave limpa + transfere, leave no hub sem match não prende.
- `AliasCommandRegistrationTest`, `CompassHotfixRegressionTest`, demais 70+ PASS em `./gradlew test`.

## 6. Deploy

- Artifacts: `bigbanghub-paper-0.4.3.jar`, `bigbanghub-velocity-0.4.3.jar`
- Proxy: `~/proxy/plugins/` + `config.yml` com 3 aliases (merge, não overwrite player data).
- Hub + 3 minigames: `plugins/` + restart.
- NPC `FancyNpcs/npcs.yml`: garantir `ANY_CLICK: player_command <game>` (não `send_to_server`).
  Backup `npcs.yml.bak.0.4.3`.
- Perms (LuckPerms): `bigbanghub.queue.join=true`, `bigbanghub.match.leave=true`,
  `bigbanghub.server.connect=op` (só admin). Verificar grupo default não tem `server.connect`.
- Validação live: `/version` 0.4.3 em proxy+hub+minigames, `/campominado` sem vermelho,
  bússola abre, NPC entra via queue, `/lobby` depois `/reconnect` OK, `/campominado`
  imediato abandona e entra, `/leave` do hub e do minigame OK, `/server campominado`
  direto ainda rejeitado.

---

# ADDENDUM 0.4.4 — /hub volta para o spawn do minigame (yank loop)

## Sintoma
Após 0.4.3: entra no hub, entra no campominado, `/hub` ou `/lobby` mostra
"You are now in the Hub" e em seguida "Partida em andamento encontrada!
Reconectando..." — volta ao spawn do minigame. Loop a cada tentativa.

## Causa
0.4.3 passou a marcar `DISCONNECTED` corretamente na chegada ao hub
(reconciliação). Com `match.auto-reconnect: true`, `checkAndHandleReconnect`
reconecta automaticamente toda chegada ao hub com pendência — inclusive as
voluntárias (`/server hub`, `/hub` de outro plugin). Saída voluntária parecia
desconexão e era puxada de volta.

## Fix (0.4.4, só Velocity)
- Novo `onServerPreConnect`: transferência voluntária para o hub com partida
  não-terminal abandona a partida ANTES de transferir (`abandonMatch` +
  "Você saiu da partida."). Chegada ao hub fica sem pendência: sem yank.
  Login fresco (sem servidor anterior) é ignorado: crash recovery preservado.
- Registro `/leave` dividido: base `/leave`+`/sair` garantido; `/hub` e `/lobby`
  registrados separadamente com try-catch individual (conflito num alias não
  derruba o comando todo). Mesmo se `/hub` for de outro plugin, o pre-connect
  cobre: qualquer ida voluntária ao hub abandona.
- Testes: `voluntaryHubTransferAbandonsActiveMatchInsteadOfYankingBack`,
  `freshLoginToHubPreservesPendingReconnectForCrashRecovery`,
  `switchToOtherMinigameDoesNotAbandonViaPreConnect` (10 testes no arquivo, PASS).

## Deploy 0.4.4
- `bigbanghub-velocity-0.4.4.jar` (proxy) + `bigbanghub-paper-0.4.4.jar`
  (hub + bedward + campominado + hg, só strings de versão no Paper).
- Backups `~/backups/bigbanghub_0.4.4_20260903_134200` (brainiac) e
  `~/backup/bigbanghub_0.4.4_20260903_134200` (ubuntu2).

---

# ADDENDUM 0.4.5 — Gerenciamento externo: Hub não presume partida

**Problema:** desenvolvendo o minigame (sem integração que crie partidas), o
mantenedor entrava no backend e era auto-admitido na partida auto-criada —
comandos de fila respondiam "já possui partida", travando o desenvolvimento.
E um futuro survival (entrada livre) não pode ser tratado como partida.

**Mudança (só Paper):** com `auto-create-match: false` e nenhuma partida aberta,
`handlePlayerJoin` deixa o jogador livre (sem ticket, rastreio ou bounce) e
`handlePlayerQuit` só sinaliza quem foi realmente admitido (sem `DISCONNECTED`
fantasma). Default `auto-create-match: true` inalterado (compat live), travado
por teste. Modo manutenção = `auto-create-match: false` + `accepting-players:
false` + fila do jogo off. Survival/criativo = `server.role: GENERIC`.
Ver `MINIGAME_INTEGRATION.md` §§6, 10–11 e `OPERATIONS.md` §5.
