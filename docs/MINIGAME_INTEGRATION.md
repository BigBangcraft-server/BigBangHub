# Guia de Integração de Minigames (BigBangHub 0.3.0)

Este documento orienta desenvolvedores de minigames (como Campo Minado, BedWars ou HG) a integrar seus plugins com o BigBangHub 0.3.0, usufruindo de roteamento pelo proxy, admissão segura por tickets, gerenciamento de ciclo de vida e retorno automático ao Hub.

---

## 1. Dependência no `build.gradle`

Adicione a API do BigBangHub ao projeto do seu minigame:

```groovy
dependencies {
    compileOnly 'com.bigbangcraft:bigbanghub-api:0.3.0'
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

## 4. Exemplo Completo de Controlador de Minigame

```java
package com.example.minigame;

import com.bigbangcraft.hub.api.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

public class GameController {
    private final BigBangHubApi hub;
    private MatchHandle currentMatch;
    private Instant matchStartTime;

    public GameController(BigBangHubApi hub) {
        this.hub = hub;
        registerEventListeners();
    }

    private void registerEventListeners() {
        hub.addMatchListener(event -> {
            if (event instanceof PlayerAdmissionAcceptedEvent accepted) {
                Player player = Bukkit.getPlayer(accepted.playerId());
                if (player != null) {
                    player.sendMessage("§aBem-vindo à partida! Aguardando demais jogadores...");
                    if (accepted.role() == ParticipantRole.SPECTATOR) {
                        player.sendMessage("§7Você entrou como espectador.");
                    }
                }
            } else if (event instanceof PlayerAdmissionRejectedEvent rejected) {
                // Notificado caso a admissão de um jogador seja recusada
            } else if (event instanceof MatchParticipantLeftEvent left) {
                // Notificado caso um jogador saia durante a partida
            }
        });
    }

    /**
     * Cria e abre uma nova partida para admissão de jogadores via fila do Hub.
     */
    public void startNewMatch() {
        MatchDefinition definition = MatchDefinition.builder()
                .gameId("campominado")
                .minPlayers(2)
                .maxPlayers(10)
                .arenaId("arena_padrao")
                .allowLateJoin(false)
                .build();

        this.currentMatch = hub.matches().create(definition);

        // Abre a partida para admissão no proxy
        currentMatch.open().thenRun(() -> {
            Bukkit.getLogger().info("Partida " + currentMatch.matchId() + " aberta para jogadores!");
        });
    }

    /**
     * Dispara a contagem regressiva quando o número mínimo de jogadores for atingido.
     */
    public void onPlayerCountReached() {
        if (currentMatch != null && currentMatch.state() == MatchState.WAITING) {
            currentMatch.startCountdown(Duration.ofSeconds(10));
        }
    }

    /**
     * Quando a contagem terminar, trava a partida e inicia o jogo.
     */
    public void onCountdownFinished() {
        if (currentMatch != null && currentMatch.state() == MatchState.COUNTDOWN) {
            currentMatch.lock().thenCompose(v -> {
                this.matchStartTime = Instant.now();
                return currentMatch.start();
            }).thenRun(() -> {
                Bukkit.broadcastMessage("§aA partida começou! Boa sorte!");
            });
        }
    }

    /**
     * Elimina um jogador e o coloca no modo espectador.
     */
    public void onPlayerDeath(UUID playerId) {
        if (currentMatch != null && currentMatch.state() == MatchState.IN_GAME) {
            currentMatch.eliminate(playerId);
            currentMatch.setSpectator(playerId);
        }
    }

    /**
     * Finaliza a partida ao encontrar um vencedor.
     */
    public void onMatchWon(UUID winnerId) {
        if (currentMatch != null && currentMatch.state() == MatchState.IN_GAME) {
            Duration duration = Duration.between(matchStartTime, Instant.now());
            MatchResult result = MatchResult.singleWinner(winnerId, duration);

            // finish() notifica o proxy e agenda o retorno seguro de todos ao Hub
            currentMatch.finish(result).thenRun(() -> {
                resetArenaAndPrepareNextMatch();
            });
        }
    }

    /**
     * Limpa o mapa/arena e sinaliza que o servidor está pronto para a próxima sessão.
     */
    private void resetArenaAndPrepareNextMatch() {
        // ... restaura blocos, limpa entidades da arena ...

        // Sinaliza ao BigBangHub que a instância está liberada
        if (currentMatch != null) {
            currentMatch.markReady().thenRun(() -> {
                Bukkit.getLogger().info("Instância limpa e pronta para a próxima partida.");
            });
        }
    }
}
```

---

## 5. Entrada Direta e Proteção do Servidor

O BigBangHub protege automaticamente seus servidores de minigame contra entradas não autorizadas:
- Se um jogador tentar usar comandos de bypass para entrar diretamente na instância (`/server backend`), o BigBangHub intercepta a entrada.
- A instância consulta o Velocity via `ADMISSION_REQUEST`. Se o jogador não possuir ticket emitido pela fila, ele é automaticamente redirecionado de volta ao Hub principal (`hubminigame`) com mensagem explicativa.
- O minigame **não precisa** implementar verificação manual de permissão ou de conexão direta.

---

## 6. Modo Auto-Create-Match

Caso o servidor opere no modo `auto-create-match: true` (padrão no `config.yml`), o BigBangHub cria e abre automaticamente a primeira sessão de partida assim que o agente se registra com sucesso no proxy Velocity, e recria uma nova partida após cada `markReady()`.

---

## 7. Integração com Parties e Coesão de Equipes (BigBangHub 0.4.0)

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

## 8. Tratamento de Reconnect e Recuperação de Estado (BigBangHub 0.4.0)

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
