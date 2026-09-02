# API de Integração (BigBangHub 0.2.0)

`bigbanghub-api` é o contrato público versionado como parte de `0.2.0`.
Integrações no mesmo build usam:

```groovy
implementation project(':bigbanghub-api')
```

Para um consumidor externo, publique o módulo com
`./gradlew :bigbanghub-api:publishToMavenLocal` e use:

```groovy
repositories { mavenLocal() }
dependencies { implementation 'com.bigbangcraft:bigbanghub-api:0.2.0' }
```

## Acesso no Paper

O plugin registra `BigBangHubApi` no `ServicesManager`:

```java
RegisteredServiceProvider<BigBangHubApi> registration =
    Bukkit.getServicesManager().getRegistration(BigBangHubApi.class);
if (registration != null) {
    BigBangHubApi hub = registration.getProvider();
    
    // Papel do servidor atual (HUB, MINIGAME, GENERIC)
    ServerRole role = hub.role();

    // Se for um servidor de minigame, mutar estado e capacidade em runtime
    hub.instance().ifPresent(instance -> {
        instance.setState(GameState.IN_GAME);
        instance.setAcceptingPlayers(false);
    });

    // Fila de espera
    hub.queues().join(player.getUniqueId(), GameId.of("campominado"))
        .thenAccept(result -> player.sendMessage(result.message()));
}
```

## Contratos Principais

- `ServerRole`: Enum explícito (`HUB`, `MINIGAME`, `GENERIC`) definindo a responsabilidade do nó na rede.
- `InstanceService`: Contrato voltado a servidores de minigame (`role == MINIGAME`). Permite que o plugin de minigame notifique o Velocity instantaneamente sobre transições de estado (`WAITING`, `IN_GAME`, `ENDING`), permissão de novas entradas (`acceptingPlayers`) e capacidade customizada (`updateCapacity`).
- `InstanceRegistry`: Consulta de instâncias ativas em runtime no Velocity (`instances()`, `instancesForGame(gameId)`, `find(instanceId)`).
- `InstanceSnapshot`: Snapshot imutável contendo `instanceId`, `gameId`, `serverName`, `sessionId`, `state`, `health`, `playerCount`, `minPlayers`, `maxPlayers`, `activeReservations`, `acceptingPlayers` e timestamp do último heartbeat.
- `Reservation`: Registro imutável de reserva de vaga com TTL (`reservationId`, `playerId`, `instanceId`, `gameId`, `state`, `createdAt`, `expiresAt`).
- `ReservationState`: `RESERVED`, `CONFIRMED`, `EXPIRED`, `CANCELLED`.
- `GameRegistry`: Jogos imutáveis carregados da configuração.
- `ServerRegistry`: Destinos lógicos configurados estaticamente.
- `QueueService`: `join`, `leave`, `status`, `contains` e `size`; chamadas seguras para concorrência e retorno assíncrono via `CompletionStage`.
- `RoutingService`: Seleção health-aware, capacity-aware e reservation-aware (`select(gameId)` e `selectInstance(gameId)`).
- `PlayerTransferService`: Transferência segura de jogadores via proxy.

## Eventos Observacionais

### Filas:
- `QueueJoinedEvent`: Jogador entrou na fila em determinada posição.
- `QueueLeftEvent`: Jogador saiu ou foi removido da fila.
- `QueueAssignedEvent`: Jogador foi despachado para uma instância.

### Instâncias e Reservas:
- `InstanceRegisteredEvent`: Nova instância registrada em runtime.
- `InstanceHealthChangedEvent`: Transição de saúde (`HEALTHY`, `SUSPECT`, `UNAVAILABLE`).
- `InstanceStateChangedEvent`: Transição de estado de partida (`WAITING`, `IN_GAME`, etc.).
- `ReservationConfirmedEvent`: Jogador conectou no servidor destino e confirmou vaga.
- `ReservationExpiredEvent`: Reserva expirou por timeout sem chegada do jogador.
- `ReservationCancelledEvent`: Reserva cancelada por desconexão ou falha de transferência.

Listeners são registrados via `addQueueListener` e `addInstanceListener`. São observacionais e publicados após a transição atômica.
