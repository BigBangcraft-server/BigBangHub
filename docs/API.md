# API de Integração (BigBangHub 0.3.0)

`bigbanghub-api` é o contrato público versionado como parte de `0.3.0`.
Integrações no mesmo build usam:

```groovy
implementation project(':bigbanghub-api')
```

Para um consumidor externo, publique o módulo com
`./gradlew :bigbanghub-api:publishToMavenLocal` e use:

```groovy
repositories { mavenLocal() }
dependencies { implementation 'com.bigbangcraft:bigbanghub-api:0.3.0' }
```

---

## 1. Acesso no Paper

O plugin registra `BigBangHubApi` no `ServicesManager`:

```java
RegisteredServiceProvider<BigBangHubApi> registration =
    Bukkit.getServicesManager().getRegistration(BigBangHubApi.class);
if (registration != null) {
    BigBangHubApi hub = registration.getProvider();
    
    // Papel do servidor atual (HUB, MINIGAME, GENERIC)
    ServerRole role = hub.role();

    // Controle de Ciclo de Vida da Partida (Match Lifecycle)
    MatchHandle match = hub.matches().create(MatchDefinition.builder()
            .gameId("campominado")
            .minPlayers(2)
            .maxPlayers(10)
            .arenaId("desert_01")
            .build());

    match.open();
}
```

---

## 2. Contratos de Partidas (`MatchManager` e `MatchHandle`)

- `MatchManager`: Gerencia e consulta sessões de partidas ativas.
  - `create(MatchDefinition)`: Cria uma nova sessão.
  - `currentMatch()`: Obtém o handle da partida em execução no nó local.
  - `activeMatch(instanceId)` / `match(matchId)` / `activeMatches()` / `activeMatchesForGame(gameId)`.
  - `matchForPlayer(playerId)`: Localiza a partida ativa onde o jogador está alocado.
  - `abortMatch(matchId, reason)`: Aborta assincronamente uma partida.
- `MatchHandle`: Controlador de uma partida específica.
  - `matchId()`: Identificador imutável.
  - `snapshot()`: Snapshot imutável da partida com contadores e capacidades.
  - `state()`: Estado atual do ciclo de vida (`MatchState`).
  - `revision()`: Revisão monotônica da máquina de estados.
  - `participants()` / `participant(playerId)`: Consulta de participantes e papéis.
  - `open()`: Transiciona `CREATED -> WAITING` e abre matchmaking.
  - `startCountdown(duration)`: Transiciona `WAITING -> COUNTDOWN`.
  - `cancelCountdown()`: Cancela contagem e retorna para `WAITING`.
  - `lock()`: Trava admissão de novos jogadores (`COUNTDOWN -> LOCKED`).
  - `start()`: Inicia o jogo (`LOCKED -> IN_GAME`).
  - `eliminate(playerId)`: Elimina jogador ativo.
  - `setSpectator(playerId)`: Transforma jogador em espectador (`ParticipantRole.SPECTATOR`).
  - `finish(MatchResult)`: Conclui a partida e agenda retorno seguro ao Hub.
  - `abort(reason)`: Aborta a partida por erro ou intervenção.
  - `markReady()`: Handshake de pós-limpeza, liberando a instância no proxy.

---

## 3. Tipos e Registros Imutáveis

- `MatchId`: Identificador único validado (ex: `0191a2b3-c4d5-7e8f-9a0b-1c2d3e4f5a6b`).
- `MatchDefinition`: Especificação de partida (`gameId`, `minPlayers`, `maxPlayers`, `allowLateJoin`, `arenaId`, `metadata`). Construído via `MatchDefinition.builder()`.
- `MatchSnapshot`: Snapshot imutável contendo estado, capacidade efetiva (`maxPlayers - (participantes + pendentes)`), datas de início/fim e resultado.
- `AdmissionTicket`: Ticket criptográfico de uso único com TTL para entrada autorizada via proxy.
- `MatchParticipant`: Registro de participante (`playerId`, `matchId`, `role`, `state`, `joinedAt`).
- `MatchResult`: Resultado com desfecho (`WIN`, `DRAW`, `ABORTED`), vencedores, duração e metadados.
- `ReturnReason`: Motivos de retorno seguro (`MATCH_FINISHED`, `MATCH_ABORTED`, `PLAYER_ELIMINATED`, `PLAYER_LEFT`, `SERVER_FAILURE`, `ADMIN_FORCE_RETURN`, `DIRECT_JOIN_REJECTED`).

---

## 4. Eventos do Ciclo de Vida da Partida

Listeners são registrados via `hub.addMatchListener(Consumer<MatchEvent>)`:

- `MatchCreatedEvent`: Nova partida criada.
- `MatchStateChangedEvent`: Transição de estado de partida (`oldState` → `newState`, com revisão monotônica).
- `PlayerAdmissionAcceptedEvent`: Admissão de jogador aprovada por ticket válido.
- `PlayerAdmissionRejectedEvent`: Admissão rejeitada (ticket expirado, inexistente ou partida cheia).
- `MatchParticipantJoinedEvent`: Participante ingressou na sessão local.
- `MatchParticipantLeftEvent`: Participante saiu da partida.
- `PlayerEliminatedEvent`: Jogador foi eliminado do minigame.
- `MatchFinishedEvent`: Partida finalizada com resultado.
- `MatchAbortedEvent`: Partida abortada.
