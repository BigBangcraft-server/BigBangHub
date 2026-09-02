# Ambiente de Testes de Integração Multi-Servidor (BigBangHub 0.4.0)

Este documento descreve a arquitetura de testes de integração automatizados simulando ponta a ponta o ciclo de vida completo de parties, filas em grupo, admissão de tickets, desconexão/reconexão e votação de revanche.

---

## 1. Arquitetura do Test Harness

O ambiente de integração é construído sem dependências de processos externos ou servidores reais em execução:

- **MockProxyServer**:
  - Implementa a interface do Velocity `ProxyServer` usando proxies dinâmicos em Java (`java.lang.reflect.Proxy`).
  - Simula resolução de jogadores por UUID e nome de usuário (`getPlayer`).
  - Simula `ConnectionRequestBuilder` com roteamento assíncrono transparente para instâncias registradas.
  - Provê scheduler em memória para tarefas repetitivas e timers.
- **Instâncias Backend Mock**:
  - Instâncias registradas via protocolo nativo (`MessagePayloads.InstanceRegister`).
  - Capacidade dinâmica e estados de jogo simulados (`WAITING`, `STARTING`, `IN_GAME`, `FINISHED`).

---

## 2. Cenário de Validação Ponta a Ponta (`FullLifecycleEndToEndIntegrationTest`)

O teste valida o fluxo completo de uma sessão de jogo:

1. **Fase 1: Formação da Party no Hub**
   - O jogador líder cria a party e envia convites para outros 2 membros.
   - Os membros aceitam o convite; a party atinge 3 jogadores no estado `IDLE`.
2. **Fase 2: Fila em Grupo e Alocação Atômica**
   - O líder entra na fila (`/queue join campominado`).
   - O despachador de matchmaking encontra capacidade suficiente e reserva 3 vagas atômicas na instância.
   - O estado da party transiciona automaticamente para `ASSIGNED`.
3. **Fase 3: Criação de Partida e Emissão de Ingressos**
   - A partida é criada e aberta (`WAITING`).
   - Tickets de admissão são emitidos com `partyId` nos metadados.
   - Membros são admitidos na sessão; party transiciona para `IN_MATCH` e a partida para `IN_GAME`.
4. **Fase 4: Desconexão e Recuperação de Sessão (Reconnect)**
   - Um membro desconecta durante a partida; seu estado vira `DISCONNECTED` e a janela de reconexão é aberta.
   - O membro reconecta dentro do prazo; seu estado é restaurado para `ACTIVE` e seus dados de partida preservados.
5. **Fase 5: Conclusão de Partida e Consenso de Revanche (Rematch)**
   - A partida é finalizada com declaração de vencedor.
   - Uma sessão de revanche é iniciada; os jogadores emitem seus votos de rematch.
   - Ao atingir 100% de consenso, o sistema confirma o rematch para o grupo.
6. **Fase 6: Finalização e Retorno ao Hub**
   - A party é liberada para `IDLE`.
   - O handshake de prontidão (`markInstanceReady`) é concluído, liberando a instância para a próxima partida.
