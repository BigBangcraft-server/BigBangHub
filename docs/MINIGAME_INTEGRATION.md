# Guia de Integração de Minigames (BigBangHub 0.4.4)

Este documento orienta desenvolvedores de minigames (como Campo Minado/`BigBangMinefield`, BedWars ou HG) a integrar seus plugins com o BigBangHub 0.4.4, usufruindo de roteamento pelo proxy, admissão segura por tickets, gerenciamento de ciclo de vida e retorno automático ao Hub.

> **Regra de ouro do Campo Minado:** o minigame **dirige a partida** (`MatchHandle`:
> countdown, eliminação, finish), mas **nunca admite, transfere ou expulsa
> jogadores por conta própria**. Admissão (tickets), transferência, reconnect,
> retorno ao Hub e rejeição de entrada direta são automáticos via
> `PaperMatchManager` + proxy. Seção 9 detalha a divisão exata.

---

## 1. Dependência no `build.gradle`

Adicione a API do BigBangHub ao projeto do seu minigame:

```groovy
dependencies {
    compileOnly 'com.bigbangcraft:bigbanghub-api:0.4.4'
}
```

No seu `plugin.yml`, declare a dependência suave ou obrigatória:

```yaml
depend: [BigBangHub]
```

---

## 2. Obtendo a API do BigBangHub

No `onEnable()` do seu plugin:

```java
import com.bigbangcraft.hub.api.BigBangHubApi;
import org.bukkit.Bukkit;

BigBangHubApi hub = Bukkit.getServicesManager().load(BigBangHubApi.class);
if (hub == null) {
    getLogger().severe("BigBangHub API não foi encontrada!");
    return;
}
```

---

## 3. Fluxo de Execução de uma Partida

```text
[Criar Sessão]
      ↓
[Abrir para Jogadores (open)]
      ↓
[Admissão via Tickets & Eventos]
      ↓
[Contagem Regressiva (startCountdown)]
      ↓
[Lock da Partida (lock)]
      ↓
[Início do Jogo (start)]
      ↓
[Eliminações e Espectadores]
      ↓
[Fim da Partida (finish)]
      ↓
[Retorno Seguro ao Hub (safe return)]
      ↓
[Limpeza da Arena & Handshake (markReady)]
```

---

## 4. Exemplo Completo: Campo Minado (`BigBangMinefield`)

Com `match.auto-create-match: true` (padrão e recomendado), o BigBangHub já cria
e abre a partida no boot e após cada `markReady()`. O minigame **obtém** o handle
via `currentMatch()` — chamar `create()` com partida ativa lança
`MatchException(ACTIVE_MATCH_EXISTS)`. Só chame `create()` se você desligou o
auto-create no `config.yml`.

```java
package com.example.campominado;

import com.bigbangcraft.hub.api.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class CampoMinadoController {
    /** Estado do tabuleiro do jogador para restaurar no reconnect (ex.: células reveladas, flags). */
    public record SavedBoard(int revealed, int flags, String arenaPos) {}

    private final BigBangHubApi hub;
    private final org.bukkit.plugin.Plugin plugin;
    private final Map<UUID, SavedBoard> savedState = new ConcurrentHashMap<>();
    private Instant matchStartTime;

    public CampoMinadoController(org.bukkit.plugin.Plugin plugin, BigBangHubApi hub) {
        this.plugin = plugin;
        this.hub = hub;
        registerEventListeners();
    }

    private MatchHandle current() {
        return hub.matches().currentMatch().orElseThrow(() ->
                new IllegalStateException("Sem partida ativa (auto-create desligado?)"));
    }

    private void registerEventListeners() {
        hub.addMatchListener(event -> {
            // 1) Jogador admitido via fila -> colocar NA ARENA (não no spawn do mundo).
            //    Nunca dê spawn no PlayerJoinEvent: a admissão é assíncrona (~1s, 20 ticks)
            //    e o jogador pode ser rejeitado (sem ticket) e retornado ao Hub.
            if (event instanceof PlayerAdmissionAcceptedEvent accepted) {
                Player player = Bukkit.getPlayer(accepted.playerId());
                if (player != null) {
                    if (accepted.role() == ParticipantRole.SPECTATOR) {
                        colocarNaTribuna(player);
                    } else {
                        colocarNaArena(player);
                        verificarInicioContagem();
                    }
                }
            } else if (event instanceof PlayerAdmissionRejectedEvent rejected) {
                // Automático: jogador já está sendo retornado ao Hub. Só logar.
                Bukkit.getLogger().warning("Admissão recusada: " + rejected.reason());
            } else if (event instanceof PlayerReconnectedEvent reconnected) {
                // 2) Jogador voltou dentro da janela (60s): restaurar estado do jogo.
                Player player = Bukkit.getPlayer(reconnected.playerId());
                if (player != null) {
                    restaurarEstado(player, savedState.remove(reconnected.playerId()));
                    player.sendMessage("§aProgresso restaurado. Boa sorte!");
                }
            } else if (event instanceof MatchParticipantLeftEvent left) {
                // 3) Saída definitiva (timeout de reconnect, fim de partida, /leave).
                savedState.remove(left.playerId());
                verificarEncerramentoPorWO();
            } else if (event instanceof MatchStateChangedEvent changed) {
                if (changed.newState() == MatchState.WAITING
                        && hub.matches().currentMatch().isEmpty()) {
                    // Partida anterior terminou e o auto-create ainda não recriou:
                    // nada a fazer, o BigBangHub recria sozinho após markReady().
                }
            }
        });

        // 4) Salvar estado ao sair (Bukkit puro; o BigBangHub marca DISCONNECTED sozinho).
        Bukkit.getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler
            public void onQuit(org.bukkit.event.player.PlayerQuitEvent e) {
                if (emAndamento() && estaNaArena(e.getPlayer())) {
                    savedState.put(e.getPlayer().getUniqueId(), capturarEstado(e.getPlayer()));
                }
            }
        }, plugin);
    }

    /** Dispara a contagem quando o mínimo for atingido (ainda em WAITING). */
    public void verificarInicioContagem() {
        MatchHandle match = current();
        if (match.state() == MatchState.WAITING
                && match.snapshot().participantCount() >= match.snapshot().minPlayers()) {
            match.startCountdown(Duration.ofSeconds(10));
        }
    }

    /** Contagem terminou: travar e iniciar. */
    public void onCountdownFinished() {
        MatchHandle match = current();
        if (match.state() == MatchState.COUNTDOWN) {
            match.lock().thenCompose(v -> {
                this.matchStartTime = Instant.now();
                anunciarTabuleiroGerado();
                return match.start();
            });
        }
    }

    /** Jogador pisou na mina errada: elimina e vira espectador. */
    public void onPlayerExploded(UUID playerId) {
        MatchHandle match = current();
        if (match.state() == MatchState.IN_GAME) {
            match.eliminate(playerId).thenCompose(v -> match.setSpectator(playerId));
        }
    }

    /** Último sobrevivente: finaliza. O finish() retorna todos ao Hub sozinho (~2s). */
    public void onMatchWon(UUID winnerId) {
        MatchHandle match = current();
        if (match.state() == MatchState.IN_GAME) {
            Duration duration = Duration.between(matchStartTime, Instant.now());
            match.finish(MatchResult.singleWinner(winnerId, duration))
                 .thenRun(this::resetarArenaEPrepararProxima);
        }
    }

    /**
     * Limpa a arena E SÓ DEPOIS chama markReady().
     * Sem markReady a instância fica presa à partida antiga e a fila para:
     * `/bbhub instances` mostra a partida antiga e ninguém mais entra.
     */
    private void resetarArenaEPrepararProxima() {
        restaurarBlocosOriginais();
        removerEntidadesDaArena();
        current().markReady().thenRun(() ->
            Bukkit.getLogger().info("Arena limpa. Nova partida auto-criada."));
    }
}
```

### O que cada chamada faz no fio (proxy)

| Chamada do minigame | Mensagem enviada | Efeito no proxy |
|---|---|---|
| `open()` | `MATCH_CREATE` + `MATCH_STATE_CHANGE(WAITING)` | Instância entra no roteamento; fila despacha |
| `startCountdown/lock/start` | `MATCH_STATE_CHANGE` | `WAITING→COUNTDOWN→LOCKED→IN_GAME`; lock fecha admissão |
| `eliminate/setSpectator` | `PARTICIPANT_STATE_CHANGE` | Proxy atualiza papel/estado (vale para HUD e capacidade) |
| `finish(result)` | `MATCH_FINISH` + retorno em ~2s | Jogadores ao Hub; pós-jogo `/playagain`/`/rematch` (15s) |
| `abort(reason)` | `MATCH_ABORT` | `ABORTED`; todos ao Hub imediatamente |
| `markReady()` | `INSTANCE_READY` | Desassocia instância; libera próxima partida/fila |

---

## 5. Entrada Direta e Proteção do Servidor

O BigBangHub protege automaticamente seus servidores de minigame contra entradas não autorizadas:
- Se um jogador tentar entrar diretamente na instância (`/server campominado`), o backend consulta o Velocity via `ADMISSION_REQUEST`. Sem ticket válido, a admissão é rejeitada (`DIRECT_JOIN_REJECTED`) e o jogador retorna ao Hub (`hubminigame`) — sem kick punitivo. **Isso é o comportamento correto, não um bug.**
- NPCs (FancyNpcs) e menus **devem** usar `player_command <alias>` / ação `QUEUE`, nunca `send_to_server`: transferência direta pula fila→reserva→ticket e será rejeitada. Ver `FancyNpcs/npcs.yml` no Hub: `ANY_CLICK: { action: player_command, value: campominado }`.
  O `player_command campominado` no Hub resolve pelo alias **local** do Hub Paper (só vale no Hub); no proxy não há alias global desde 0.4.6, então `/campominado` digitado no backend campominado chega ao BigBangMinefield.
- Ação `SERVER` em menus exige a permissão `bigbanghub.server.connect` (padrão: `op` desde 0.4.3). Menus públicos usam `QUEUE`.
- O minigame **não precisa** (e não deve) implementar verificação manual de entrada direta.

---

## 6. Modo Auto-Create-Match e Gerenciamento Externo

Com `match.auto-create-match: true` (padrão), o BigBangHub cria e abre automaticamente a partida no boot e recria uma nova após cada `markReady()`. Nesse modo **não chame `create()`**: resulta em `MatchException(ACTIVE_MATCH_EXISTS)`. Use `hub.matches().currentMatch()`.

Só chame `create()` + `open()` manualmente se você desligou o auto-create (`auto-create-match: false`) — útil quando a arena precisa ser gerada antes de abrir.

### Minigame ainda não integrado? Desenvolvimento e manutenção (0.4.5)

O Hub **nunca presume uma partida que ninguém criou**. Com `auto-create-match: false`
e nenhuma partida aberta, jogadores que entram **ficam livres**: sem ticket, sem
rastreio, sem bounce ao Hub, comandos Paper livres. É o modo para desenvolver o
plugin do minigame (ex.: `BigBangMinefield`) ou fazer manutenção:

```yaml
# plugins/BigBangHub/config.yml no backend (ex.: campominado)
match:
  auto-create-match: false   # minigame cria via API; Hub não presume partida
server:
  role: MINIGAME
  instance:
    instance-id: campominado
    game-id: campominado
    server-name: campominado
    accepting-players: false # invisível à fila enquanto não há partida aberta
```

Quando seu plugin criar + `open()` via API, o `open()` publica `WAITING` +
aceitando e a fila volta a rotear sozinha; `finish`/`markReady()` seguem normais.
Na saída (`quit`), quem nunca foi admitido não gera nenhum sinal ao proxy
(sem `DISCONNECTED` fantasma). Requer restart do backend (o flag é lido no boot).

> Fila silenciosa: com `accepting-players: false` a fila não tem para onde
> despachar — durante a manutenção, desabilite também `queue.enabled: false` do
> jogo no proxy (`games.yml`) e no Hub, para o jogador receber "temporariamente
> indisponível" em vez de esperar para sempre. Reative com `/bbhub reload`
> (proxy, in-game) e `bbhub reload` no Hub.

---

## 7. Integração com Parties e Coesão de Equipes (BigBangHub 0.4.4)

A partir da versão 0.4.0, o BigBangHub transporta automaticamente a associação de grupo (`PartyId`) dos jogadores até o backend Paper:

```java
// Descobrir se um jogador pertence a uma party
Optional<PartyId> partyId = participant.partyId();

// Obter todos os membros de uma mesma party presentes na partida:
if (partyId.isPresent()) {
    Collection<MatchParticipant> teammates = currentMatch.participantsOfParty(partyId.get());
    // Aloque todos no mesmo time/esquadrão automaticamente!
}
```

Invariantes de Coesão:
- O vínculo de party permanece estritamente preservado durante transições de eliminação (`ELIMINATED`) ou espectador (`SPECTATING`).
- Ao término da partida via `finish(result)`, os membros da party retornam ao Hub de forma coordenada, preservando o grupo com estado revertido para `IDLE`.

---

## 8. Tratamento de Reconnect e Recuperação de Estado (BigBangHub 0.4.4)

Quando um jogador desconecta durante uma partida em andamento, sua vaga é mantida reservada no estado `DISCONNECTED` durante a janela configurada em `match.reconnect-timeout`.

Ao reconectar, o BigBangHub dispara o evento `PlayerReconnectedEvent` no barramento de eventos:

```java
hubApi.addMatchListener(event -> {
    if (event instanceof PlayerReconnectedEvent reconnected) {
        UUID playerId = reconnected.playerId();
        Player player = Bukkit.getPlayer(playerId);
        if (player != null) {
            // Restaurar localização na arena, inventário salvo, kit ou equipe
            restorePlayerGameState(player);
        }
    }
});
```

Se o jogador não retornar antes da expiração do timeout, o evento `MatchParticipantLeftEvent` é disparado com motivo `"reconnect expired"`, liberando definitivamente a vaga e permitindo que a lógica de minigame aplique penalidade de desistência ou auto-vitória aos oponentes.

> **Saída voluntária ≠ crash (0.4.4):** `/leave`, `/hub`, `/lobby`, `/sair` ou
> `/server hub` **abandonam** a partida no proxy antes de transferir — sem janela
> de reconnect e sem auto-reconnect puxando o jogador de volta. Crash/queda
> mantém `DISCONNECTED` + auto-reconnect. O minigame não precisa distinguir:
> abandono chega como `MatchParticipantLeftEvent("player left" /
> "voluntary hub transfer")`, crash como `PlayerReconnectedEvent` ou
> `MatchParticipantLeftEvent("reconnect expired")`.

---

## 9. Quem faz o quê: BigBangHub × minigame (Campo Minado)

| Responsabilidade | Dono | Detalhe |
|---|---|---|
| Fila, roteamento, reserva, ticket, transferência | BigBangHub | Automático; minigame não chama nada |
| Admissão na chegada (`ADMISSION_REQUEST`) | `PaperMatchManager` | Automático via `PaperInstanceListener` (~20 ticks após join) |
| Rejeição de entrada direta | `PaperMatchManager` | Automático + retorno ao Hub (+ kick fallback) |
| `DISCONNECTED` ao sair / `LEFT` no timeout | `PaperMatchManager` + proxy | Automático; minigame só escuta eventos |
| Auto-reconnect e `/reconnect` (ticket `isReconnect`) | Proxy | Automático, inclusive em `LOCKED`/`IN_GAME` |
| Abandono em `/leave`/`/hub`/`/lobby`/`/server hub` | Proxy (0.4.4) | Automático via pre-connect |
| Retorno ao Hub no `finish`/`abort` (~2s) | `PaperMatchHandle` | Automático; minigame reseta a arena em paralelo |
| Contagem, lock, start | **Minigame** | `startCountdown` → `lock` → `start` |
| Spawn na arena / tribuna | **Minigame** | No `PlayerAdmissionAcceptedEvent` (nunca no `PlayerJoinEvent`) |
| Salvar estado ao sair | **Minigame** | `PlayerQuitEvent` (Bukkit) + mapa próprio |
| Restaurar estado no reconnect | **Minigame** | No `PlayerReconnectedEvent` |
| Eliminar / espectador | **Minigame** | `eliminate()` + `setSpectator()` |
| Declarar vencedor | **Minigame** | `finish(MatchResult.singleWinner(...))` |
| Resetar arena + liberar instância | **Minigame** | Reset físico e **depois** `markReady()` |
| Times por party | **Minigame** | `participant.partyId()` + `participantsOfParty()` |

---

## 10. Checklist de integração: servidor `campominado` (live)

Backend `brainiac:~/bigbangcraft/minigames/campominado`:
1. `plugins/bigbanghub-paper-0.4.5.jar` (remover jar antigo — dois jars carregam duas vezes).
2. `plugins/BigBangHub/config.yml`: `server.role: MINIGAME` + bloco `instance`
   (`instance-id: campominado`, `game-id: campominado`, `server-name: campominado`
   igual ao `velocity.toml`), `capacity` igual ao `MatchDefinition` do auto-create.
   Produção integrada: `auto-create-match: true`. Em desenvolvimento/manutenção:
   `auto-create-match: false` + `accepting-players: false` (seção 6).
3. `BigBangMinefield.jar` com `depend: [BigBangHub]` no `plugin.yml` e controlador
   conforme seção 4 (nunca `create()` com auto-create ligado).
4. NPCs ficam **no Hub**, não no minigame: `hubminigame/plugins/FancyNpcs/npcs.yml`
   com `player_command` (nunca `send_to_server`).
5. Validar no boot: `Enabling BigBangHub v0.4.5` + `Auto-created and opened new match`
   no `logs/latest.log`; no proxy: `Discovered and registered online backend
   instance campominado for game campominado`.

### Servidor sem partidas: survival com entrada livre

Minigame nenhum, fila nenhuma, ticket nenhum: use `server.role: GENERIC`
(ou omita `role` — o padrão é `GENERIC`):

```yaml
server:
  role: GENERIC
```

Nesse papel o plugin não registra proteção de lobby, bússola, agente de instância
nem gerenciador de partidas: entrada 100% livre inclusive via `/server survival`
direto, nenhum rastreio no proxy. Comandos de fila no proxy respondem
"temporariamente indisponível" (survival não é jogo). É o papel certo para
survival, criativo e eventos.

> **Armadilha FancyNpcs:** ele **salva `npcs.yml` ao desligar**. Editar o arquivo com
> o Hub ligado e depois reiniciar **reverte** a edição. Procedimento: edite, depois
> `fancynpcs reload` no console do Hub **sem reiniciar**. Verificado em produção (0.4.3).

---

## 11. Erros comuns do minigame (e como diagnosticar)

| Sintoma | Causa | Correção |
|---|---|---|
| `ACTIVE_MATCH_EXISTS` no boot | `create()` manual com auto-create ligado | Usar `currentMatch()`; criar só com auto-create `false` |
| Jogador cai no spawn do mundo, não na arena | Spawn no `PlayerJoinEvent` antes da admissão | Spawnar no `PlayerAdmissionAcceptedEvent` |
| Fila anda mas ninguém entra; `/bbhub instances` mostra partida antiga | `markReady()` não chamado após reset | Sempre `markReady()` depois do reset físico |
| `/server campominado` volta ao Hub | Correto por design (`DIRECT_JOIN_REJECTED`) | Entrar via fila, NPC, bússola ou `/campominado` |
| Jogador reconecta sem progresso | Estado não salvo/restaurado | Salvar no Bukkit `PlayerQuitEvent`, restaurar no `PlayerReconnectedEvent` |
| `/hub` volta ao minigame (loop) | Versão < 0.4.4 (auto-reconnect puxa de volta) | Atualizar proxy para 0.4.4+ |
| NPC expulsa com "no active admission ticket" | NPC com `send_to_server` | Trocar para `player_command` + reload (seção 10) |
| Travado "em partida" desenvolvendo o minigame | Auto-create presume partida que ninguém criou | `auto-create-match: false` + `accepting-players: false` (seção 6); fila do jogo off na manutenção |
| Survival/criativo com entrada livre | Papel com gerência de partida | `server.role: GENERIC` (seção 10); sem fila, sem ticket, sem rastreio |
