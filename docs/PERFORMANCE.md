# Validação de Performance e Memória (BigBangHub 0.4.0)

Este documento detalha os benchmarks de carga, medições de latência de matchmaking, estratégias de limpeza por sweeper e testes de vazamento de memória para o subsistema de Party, Matchmaking em Grupo, Reconnect e Rematch.

---

## 1. Escala e Carga Concorrente de Parties

O serviço `InMemoryPartyService` utiliza sincronização baseada em `ReentrantLock` com granularidade otimizada e coleções de alta performance em memória:

- **Volume Testado**: 1.000 parties criadas simultaneamente sob pool de 8 threads de execução concorrente.
- **População**: Cada party provisionada com 1 líder e 2 membros (total de 3.000 jogadores manipulados simultaneamente).
- **Tempo Total de Operação**: ~34 ms para provisionamento completo (criação, convites, aceites mútuos e validações).
- **Integridade Concorrente**:
  - CAS/Lock reentrante previne condições de corrida em transferências de liderança e convites simultâneos.
  - Zero deadlocks detectados.

---

## 2. Latência de Matchmaking e Admissão em Grupo

Para validar a performance de despacho sob mix de jogadores individuais e parties:

- **Cenário de Teste**:
  - 500 admissões de jogadores solo.
  - 100 admissões de parties completas (grupos de 4 jogadores).
  - Emissão de `AdmissionTicket` + validação criptográfica de token + registro no `InMemoryMatchRegistry`.
- **Métricas Observadas**:
  - **p50**: ~0.011 ms (11 microssegundos).
  - **p99**: ~0.110 ms (110 microssegundos).
  - **Meta Obrigatória**: p99 < 5.0 ms.
  - **Margem de Performance**: ~45x mais rápido do que a meta estrita exigida.

---

## 3. Sweeper e Expiração Automática

Tarefas de manutenção em background (`sweepLivenessAndReservations`) executam a cada 1 segundo no proxy Velocity, garantindo limpeza atômica de estruturas temporárias:

1. **Convites de Party Expirados**:
   - Varredura em `party.invitedPlayers` e remoção automática de `pendingInvitesByTarget`.
   - Disparo do evento `PartyInviteExpiredEvent`.
2. **Desconexões de Membros e Líderes**:
   - `disconnectedMembers` monitora a janela de tolerância (`leaderDisconnectGrace`, padrão 30s).
   - Promove sucessão automática de líder ou disband da party caso o líder não retorne a tempo.
3. **Tickets de Admissão**:
   - `AdmissionTicketService.sweepExpired(Instant now)` remove tickets que ultrapassaram o TTL de conexão (padrão 10s).
4. **Tombstones de Partidas**:
   - `InMemoryMatchRegistry.sweepTombstones(Instant now)` purga registros de partidas finalizadas há mais de 30 segundos, liberando memória.
5. **Sessões de Rematch e Play Again**:
   - `RematchService.sweep(Instant now)` purga sessões cujo tempo de votação expirou (padrão 15s) e limpa o índice reverso de jogadores `matchByPlayer`.

---

## 4. Teste de Vazamento de Memória (Memory Leak Check)

Um teste automatizado simula 500 ciclos contínuos de criação de party, convite, entrada, saída de membros e disband de liderança:

- Após cada ciclo, o mapeamento `playerPartyMap`, a lista de parties ativas e a fila de convites pendentes retornam a **0 itens**.
- Inspecionado e validado por `PartyAndMatchPerformanceTest.testMemoryLeakCheckAfterDisbandAndQuitCycles`.
