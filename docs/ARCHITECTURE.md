# Arquitetura (BigBangHub 0.2.0)

```text
Player
  -> Velocity 4.1.1 + BigBangHub Velocity
       ├── InMemoryInstanceRegistry (Instances, Health, Sessions)
       ├── InMemoryReservationService (Slots com TTL)
       ├── InMemoryQueueService (FIFO, Event-driven)
       └── InstanceAwareRoutingService (FILL_WAITING, LEAST_PLAYERS, ROUND_ROBIN)
  -> Hub Paper (Role: HUB)
       └── Compass / Menus / Lobby Protection / Alias Commands
  -> Minigame Paper (Role: MINIGAME)
       └── PaperInstanceAgent (Heartbeats, SessionId, State, Capacity)
```

## 1. Módulos e Responsabilidades

- **`bigbanghub-api`**: Contratos puros, records imutáveis, enums de lifecycle (`ServerRole`, `InstanceHealth`, `ReservationState`, `GameState`), eventos observacionais e interfaces (`InstanceRegistry`, `InstanceService`, `QueueService`, `RoutingService`, `PlayerTransferService`). Livre de dependências externas.
- **`bigbanghub-common`**: Implementações centrais desacopladas de Minecraft:
  - `InMemoryInstanceRegistry`: Tabela thread-safe de instâncias ativas, substituição atômica de sessões e sweep de liveness.
  - `InMemoryReservationService`: Alocação concorrente de vagas com TTL e expiração automática.
  - `InMemoryQueueService`: Fila com ordem FIFO estrita e rastreamento de tempos de espera.
  - `InstanceAwareRoutingService`: Políticas de roteamento (`FILL_WAITING`, `LEAST_PLAYERS`, `ROUND_ROBIN`) com desempate determinístico.
  - `ProtocolCodec` e `MessagePayloads`: Serialização binária em envelopes `BBH1` com versionamento e HMAC opcional.
  - `ConfigLoader`: Parser rigoroso com validação de snapshots atômicos imutáveis.
- **`bigbanghub-velocity`**: Orquestrador do cluster. Mantém o registro em memória, gerencia o ciclo de vida das instâncias e reservas, despacha filas orientado a eventos, confirma conexões via `ServerPostConnectEvent`, redireciona quedas de volta ao Hub via `KickedFromServerEvent`, e provê comandos administrativos e métricas leves.
- **`bigbanghub-paper`**: Plugin unificado para Paper 26.2 (Java 25).
  - Em `role: HUB`: Ativa menu de bússola, proteções completas de lobby, atalhos de minigame e fila.
  - Em `role: MINIGAME`: Ativa o `PaperInstanceAgent`, publica o estado via `InstanceService`, envia heartbeats periódicos a cada 3s e sincroniza entradas/saídas de jogadores.

---

## 2. Topologia Operacional Real

- **`ubuntu2` (10.8.0.1)**:
  - Velocity 4.1.1 na porta `25565`.
  - Executa `bigbanghub-velocity.jar`.
  - Gerencia o cluster de instâncias e as filas globais.
- **`brainiac` (10.8.0.2)** via WireGuard:
  - Hub Paper 26.2 na porta `25565` (`role: HUB`).
  - BedWars na porta `25566` (`role: MINIGAME`).
  - Campo Minado na porta `25567` (`role: MINIGAME`, BigBangMinefield).
  - HG na porta `25568` (`role: MINIGAME`).
  - Java 25.
- **Zero Middleware Externo**: Não há dependência de Redis, MySQL, Kafka ou RabbitMQ. O cluster se comunica inteiramente através do canal de plugin messaging `bigbanghub:main`.

---

## 3. Fluxo de Vida de uma Partida

```
1. Minigame Boot
   └─ PaperInstanceAgent gera novo UUID sessionId e agenda heartbeat (3s).
2. Registration
   └─ Paper envia INSTANCE_REGISTER com sessionId, capacidade e estado WAITING.
   └─ Velocity valida identidade, registra como HEALTHY e responde INSTANCE_REGISTER_ACK.
   └─ Velocity dispara dispatchQueue(gameId).
3. Player Queue Join
   └─ Jogador entra na fila no Hub (/queue join ou bússola).
   └─ Velocity executa dispatchQueue(gameId).
4. Slot Reservation & Transfer
   └─ Velocity seleciona a melhor instância (ex: FILL_WAITING).
   └─ Tenta reservar slot via InMemoryReservationService.reserve(...).
   └─ Se aprovado, retira jogador da fila e inicia transferência.
5. Arrival Confirmation
   └─ Jogador conecta no destino -> ServerPostConnectEvent no Velocity confirma reserva (CONFIRMED).
6. Failure & Fallback
   └─ Se a conexão falhar ou timeout expirar -> reserva CANCELLED/EXPIRED, vaga liberada, jogador re-enfileirado.
   └─ Se o jogador for kickado durante uma partida -> KickedFromServerEvent redireciona de volta ao Hub.
```

---

## 4. Despacho Orientado a Eventos vs. Polling

O sistema elimina expressamente polling a cada tick nas filas:
- A fila dorme enquanto não há vagas ou enquanto não há jogadores.
- Despachos ocorrem somente sob eventos de gatilho:
  - `InstanceRegisteredEvent`
  - `InstanceHealthChangedEvent` (recuperação para `HEALTHY`)
  - `InstanceStateChangedEvent` (mudança para `WAITING`)
  - `QueueJoinedEvent`
  - `ReservationExpiredEvent` / `ReservationCancelledEvent`
  - Falha de transferência com retorno à fila

---

## 5. Garantias de Concorrência e Invariantes

- **Exclusividade de Reserva**: Um jogador pode possuir no máximo 1 reserva ativa em todo o proxy.
- **Capacidade Efetiva**: Uma instância nunca ultrapassa seu `maxPlayers`:
  $$playerCount + activeReservations \le maxPlayers$$
- **Consistência de Reinicialização**: Mensagens de rádio antigas de um processo anterior que reiniciou são imediatamente rejeitadas pelo Velocity através de validação de `sessionId`.
- **Thread Safety**: Operações em memória usam mapas concorrentes e travas ultra-curtas de sincronização granular de instâncias/filas sem qualquer I/O bloqueante dentro do lock.
