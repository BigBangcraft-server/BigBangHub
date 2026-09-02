# Arquitetura (BigBangHub 0.3.0)

```text
Player
  -> Velocity 4.1.1 + BigBangHub Velocity
       ├── InMemoryMatchRegistry (MatchSessions, Revisions, Capacities, History)
       ├── AdmissionTicketService (Single-use Tickets, Nonces, Expiry)
       ├── InMemoryInstanceRegistry (Instances, Health, Sessions)
       ├── InMemoryReservationService (Slots com TTL)
       ├── InMemoryQueueService (FIFO, Event-driven)
       └── InstanceAwareRoutingService (FILL_EXISTING_MATCH, LEAST_PLAYERS)
  -> Hub Paper (Role: HUB)
       └── Compass / Menus / Lobby Protection / Alias Commands
  -> Minigame Paper (Role: MINIGAME)
       ├── PaperMatchManager (MatchHandle, MatchLifecycle)
       └── PaperInstanceAgent (Heartbeats, SessionId, State, Capacity)
```

---

## 1. Módulos e Responsabilidades

- **`bigbanghub-api`**: Contratos puros e imutáveis de partidas e instâncias:
  - `MatchManager`, `MatchHandle`, `MatchDefinition`, `MatchSnapshot`, `MatchId`.
  - `MatchState` (`CREATED`, `WAITING`, `COUNTDOWN`, `LOCKED`, `IN_GAME`, `ENDING`, `FINISHED`, `ABORTED`).
  - `ParticipantRole` (`PLAYER`, `SPECTATOR`) e `ParticipantState` (`RESERVED`, `ADMITTED`, `ACTIVE`, `ELIMINATED`, `SPECTATING`, `LEAVING`, `LEFT`, `DISCONNECTED`).
  - `AdmissionTicket`, `MatchResult`, `ReturnReason`, `DisconnectPolicy`.
  - Eventos de partidas (`MatchCreatedEvent`, `MatchStateChangedEvent`, `PlayerAdmissionAcceptedEvent`, `PlayerEliminatedEvent`, `MatchFinishedEvent`, etc.).
  - Livre de dependências externas.
- **`bigbanghub-common`**: Implementações centrais desacopladas de Minecraft:
  - `InMemoryMatchRegistry`: Registro em memória de partidas ativas, controle de capacidade efetiva, proteção contra replay, retenção de tombstones e handshake de limpeza (`markInstanceReady`).
  - `MatchStateMachine`: Máquina de estados thread-safe com proteção CAS e controle monotônico de revisões (`revision`).
  - `AdmissionTicketService`: Emissor e validador de ingressos de uso único com TTL para prevenção de conexões diretas.
  - `MatchEventBus`: Barramento concorrente e isolado de eventos de partidas.
  - `InMemoryInstanceRegistry`, `InMemoryReservationService`, `InMemoryQueueService`.
  - `ProtocolCodec` e `MessagePayloads`: Serialização binária em envelopes `BBH1` (mensagens 1 a 23) com suporte a HMAC-SHA256.
  - `ConfigLoader`: Carregamento estrito com snapshots imutáveis.
- **`bigbanghub-velocity`**: Orquestrador global de partidas e rede.
  - Roteia jogadores da fila priorizando partidas abertas com capacidade (`FILL_EXISTING_MATCH`).
  - Emite `AdmissionTicket` e valida handshake de admissão com os nós de minigame.
  - Valida identidade de remetente e coerência de sessão em cada mensagem.
  - Conduz retorno seguro ao Hub (`safeReturnPlayerToHub`) em caso de término, abort, eliminação ou tentativa de conexão direta sem ticket.
  - Provê comandos de inspeção e intervenção (`/bbhub matches`, `/bbhub match <id> abort`, `/bbhub return <player>`).
- **`bigbanghub-paper`**: Plugin unificado para Paper 26.2.
  - Em `role: HUB`: Bússola, atalhos de minigame e fila.
  - Em `role: MINIGAME`: Instancia `PaperMatchManager`, intercepta conexões diretas para validar tickets com o Velocity, executa auto-criação de partida e gerencia o ciclo de vida via `MatchHandle`.

---

## 2. Topologia Operacional Real

- **`ubuntu2` (10.8.0.1)**:
  - Velocity 4.1.1 na porta `25565`.
  - Executa `bigbanghub-velocity.jar`.
  - Gerencia o cluster de instâncias, partidas globais e filas.
- **`brainiac` (10.8.0.2)** via WireGuard:
  - Hub Paper 26.2 na porta `25565` (`role: HUB`).
  - BedWars na porta `25566` (`role: MINIGAME`).
  - Campo Minado na porta `25567` (`role: MINIGAME`, BigBangMinefield).
  - HG na porta `25568` (`role: MINIGAME`).
  - Java 25.
- **Zero Middleware Externo**: Não há dependência de Redis, MySQL, Kafka ou RabbitMQ. O cluster se comunica inteiramente através do canal de plugin messaging `bigbanghub:main`.

---

## 3. Fluxo Completo de uma Partida

```text
1. Boot & Auto Match Creation
   └─ PaperInstanceAgent se registra no Velocity (INSTANCE_REGISTER).
   └─ Velocity confirma registro (INSTANCE_REGISTER_ACK).
   └─ Minigame cria MatchDefinition e chama match.open() (MATCH_CREATE & MATCH_STATE_CHANGE: WAITING).
2. Matchmaking & Ticket Issuance
   └─ Jogador entra na fila no Hub.
   └─ Velocity detecta MatchSession em WAITING com capacidade disponível.
   └─ Velocity reserva slot no servidor e emite AdmissionTicket criptográfico de uso único.
   └─ Jogador é transferido para a instância do minigame.
3. Admission Handshake
   └─ Jogador conecta na instância Paper.
   └─ Instância Paper envia ADMISSION_REQUEST ao Velocity com os dados do ticket.
   └─ Velocity valida ticket, consome o token e responde ADMISSION_RESPONSE (accepted=true).
   └─ Jogador é admitido como participante ativo na partida.
4. Game Progression
   └─ Ao atingir minPlayers, minigame chama match.startCountdown(10s).
   └─ Ao concluir contagem, chama match.lock() e match.start() (MATCH_STATE_CHANGE: IN_GAME).
   └─ Jogadores eliminados são marcados via match.eliminate(id) e match.setSpectator(id).
5. Finish & Safe Return
   └─ Vencedor definido: minigame chama match.finish(result).
   └─ Velocity e Paper conduzem retorno seguro de todos os jogadores para o Hub (hubminigame).
6. Arena Reset & Cleanup Handshake
   └─ Minigame reseta blocos da arena e chama match.markReady().
   └─ Velocity recebe INSTANCE_READY, desassocia a instância da partida finalizada e a torna disponível.
```

---

## 4. Garantias de Concorrência e Invariantes

1. **Invariante de Jogador**: Um jogador pode pertencer a no máximo **uma partida ativa** em toda a rede.
2. **Invariante de Instância**: Uma instância suporta no máximo **uma partida ativa** por vez.
3. **Capacidade Efetiva**:
   $$\text{Capacidade Efetiva} = \text{maxPlayers} - (\text{participantes ativos} + \text{admissões pendentes})$$
4. **Proteção de Admissão**: Tickets de uso único e prazo de expiração curto (10s) impedem conexões diretas não autorizadas e ataques de repetição.
5. **Revisões Monotônicas**: Alterações de estado usam CAS (`expectedRevision`) impedindo pacotes concorrentes ou fora de ordem.
