# Ciclo de Vida de Instâncias, Liveness e Reservas (BigBangHub 0.3.0)

O **BigBangHub 0.3.0** introduz uma infraestrutura completa de **Runtime Instance Registry**, **Liveness Tracking com Heartbeats**, **Slot Reservations transitórias**, **Ciclo de Vida Padronizado de Partidas** e **Roteamento Resiliente Orientado a Eventos** para a rede BigBangCraft.

---

## 1. Visão Geral da Arquitetura

Na versão 0.1.0, os servidores de destino eram essencialmente estáticos (declarados em `servers.yml`). Na versão 0.2.0:
- O **Velocity** mantém uma tabela em memória de instâncias dinâmicas (`InMemoryInstanceRegistry`), indexadas por `ServerId` e `GameId`.
- Os servidores **Paper** operam com papéis explícitos (`ServerRole`):
  - `HUB`: Lobby principal, bússola, inventário protegido, proteções de mundo, comandos `/queue`.
  - `MINIGAME`: Agente leve (`PaperInstanceAgent`) de publicação de estado, capacity e heartbeats periódicos.
  - `GENERIC`: Servidores utilitários sem lobby protection.
- Toda alocação de vaga utiliza **Slot Reservations** com TTL (Time-to-Live), eliminando completamente condições de corrida e oversubscription em entradas simultâneas.
- O despacho da fila é **100% orientado a eventos** (quando instâncias registram, mudam de estado para `WAITING` ou liberam vagas). Não há polling a cada tick.

---

## 2. Estados da Instância e Liveness

Cada instância registrada possui um ciclo de vida de saúde monitorado continuamente pelo proxy Velocity:

```
                  ┌──────────────────────┐
                  │       STARTUP        │
                  └──────────┬───────────┘
                             │ INSTANCE_REGISTER
                             ▼
                  ┌──────────────────────┐
         ┌───────►│       HEALTHY        │◄────────┐
         │        └──────────┬───────────┘         │
Heartbeat│                   │ missed heartbeats   │Heartbeat
recovers │                   ▼ (> suspect threshold│recovers
         │        ┌──────────────────────┐         │
         └────────┤       SUSPECT        │         │
                  └──────────┬───────────┘         │
                             │ timeout exceeded    │
                             ▼                     │
                  ┌──────────────────────┐         │
                  │     UNAVAILABLE      ├─────────┘
                  └──────────┬───────────┘
                             │ unregister / shutdown
                             ▼
                  ┌──────────────────────┐
                  │       REMOVED        │
                  └──────────────────────┘
```

### Classificação de Saúde:
- **`HEALTHY`**: Heartbeat recente recebido dentro do intervalo configurado. Apenas instâncias `HEALTHY` recebem conexões ou alocações de fila.
- **`SUSPECT`**: Nenhum heartbeat recebido por mais de `suspect-threshold` (padrão: 5s). A instância para de receber novas atribuições enquanto aguarda recuperação.
- **`UNAVAILABLE`**: Nenhum heartbeat recebido por mais de `heartbeat-timeout` (padrão: 10s) ou desconexão explícita. Todas as reservas pendentes vinculadas a esta instância são automaticamente canceladas e limpas, liberando os jogadores.
- **Recuperação**: O recebimento de um heartbeat válido restaura imediatamente a instância para `HEALTHY` e dispara o dispatcher de filas correspondente.

---

## 3. Isolamento de Sessão e Restarts

Quando um processo de servidor minigame (ex: `campominado-01`) reinicia:
1. O novo processo gera um novo `UUID sessionId` exclusivo em sua inicialização.
2. O agente envia `INSTANCE_REGISTER` com o novo `sessionId`.
3. O Velocity detecta que o `sessionId` mudou:
   - Substitui a sessão atômica no registro.
   - Limpa e cancela quaisquer reservas órfãs deixadas pela sessão anterior.
   - Restaura a saúde para `HEALTHY`.
4. **Proteção contra mensagens antigas**: Quaisquer pacotes atrasados da sessão anterior (ex: heartbeats em buffer, state changes) que cheguem com o `sessionId` antigo são sumariamente **rejeitados** (`REJECTED_STALE_SESSION`) e descartados sem alterar o estado do servidor.

---

## 4. Sistema de Reservas de Vagas (Slot Reservations)

Para evitar que múltiplos jogadores sejam encaminhados para a mesma vaga simultaneamente (oversubscription):

1. **Tentativa Atômica**: Antes de encaminhar um jogador ou despachá-lo da fila, o Velocity invoca `InMemoryReservationService.reserve(instanceId, playerId, gameId, now)`.
2. **Capacidade Efetiva**: A reserva só é aprovada se:
   $$\text{Capacidade Efetiva} = \text{maxPlayers} - (\text{playerCount} + \text{activeReservations}) > 0$$
3. **Estados da Reserva**:
   - `RESERVED`: Vaga retida temporariamente no proxy. O jogador é enviado ao backend.
   - `CONFIRMED`: O evento `ServerPostConnectEvent` do Velocity detecta que o jogador conectou fisicamente ao servidor de destino. A reserva é confirmada e o slot reservado é convertido em player conectado no backend.
   - `EXPIRED`: Se o jogador não conectar dentro de `reservation-ttl` (padrão: 10s), a reserva expira automaticamente na varredura periódica de 1 segundo. A vaga é liberada e a fila é re-despachada.
   - `CANCELLED`: Se a transferência falhar, o jogador desconectar antes de conectar ao destino, ou o servidor destino cair, a reserva é cancelada e o slot é liberado imediatamente.

---

## 5. Estratégias de Roteamento (Routing Policy V2)

O `InstanceAwareRoutingService` avalia instâncias candidatas utilizando os seguintes filtros obrigatórios:
1. `gameId` correspondente ao solicitado;
2. `health == HEALTHY`;
3. `acceptingPlayers == true`;
4. `state == WAITING`;
5. `effectiveCapacity > 0`.

Caso múltiplas instâncias atendam aos critérios, aplica-se a estratégia configurada no jogo (`games.yml`):
- **`FILL_WAITING`** (Padrão para minigames de lobby): Prioriza instâncias que já possuem mais jogadores, preenchendo-as rapidamente para iniciar a partida. Desempate determinístico lexicográfico por `instanceId`.
- **`LEAST_PLAYERS`**: Prioriza instâncias com menor número de jogadores, balanceando a carga da rede. Desempate lexicográfico.
- **`ROUND_ROBIN`**: Alterna ciclicamente entre as instâncias disponíveis de forma determinística.

---

## 6. Despacho de Fila Orientado a Eventos

A fila de espera (`InMemoryQueueService`) mantém ordem estrita **FIFO** (First-In, First-Out) e **não executa polling a cada tick**. O despacho (`dispatchQueue(gameId)`) é disparado reativamente pelos seguintes eventos:
- Um novo jogador entra na fila;
- Uma nova instância se registra no proxy (`INSTANCE_REGISTER`);
- Uma instância recupera sua saúde (`ACCEPTED_RECOVERED`);
- O estado de uma instância muda para `WAITING` e passa a aceitar jogadores;
- Uma reserva de vaga expira ou é cancelada, liberando capacidade;
- Uma transferência falha, permitindo tentar o próximo destino elegível.

---

## 7. Segurança de Backends e Validação de Identidade

Para evitar abusos e falsificação de identidade por servidores backend comprometidos:
1. **Verificação de Origem**: O proxy verifica se o nome da conexão backend (`connection.getServerInfo().getName()`) corresponde ao `instanceId` ou às regras de allowlist configuradas (`registry.allowed`).
2. **Existência no Proxy**: Nenhuma instância pode se registrar se o `serverName` declarado não existir como servidor configurado no Velocity (`proxy.getServer(serverName)`).
3. **Limites de Payload**: Todo payload de registro e heartbeat possui validações rígidas de limites (ex: contagem negativa de jogadores, capacidade máxima > 1000, nomes > 64 caracteres são rejeitados imediatamente com `ProtocolValidationException`).
4. **Rate Limiting por Backend**: Backends que enviarem mensagens em frequência abusiva são limitados por janela de tempo na camada de rede.

---

## 8. Guia do Desenvolvedor: Integrando um Novo Minigame

Para desenvolvedores de minigames (como Campo Minado, BedWars, HG):

1. Adicione o `bigbanghub-paper.jar` na pasta `/plugins` do servidor de minigame.
2. No `config.yml` do BigBangHub no servidor de minigame:
   ```yaml
   server:
     role: MINIGAME
     instance:
       instance-id: campominado-01
       game-id: campominado
       server-name: campominado-01
       heartbeat:
         interval: 3s
       capacity:
         min-players: 2
         max-players: 10
       accepting-players: true
   ```
3. No código Java do seu plugin de minigame:
   ```java
   import com.bigbangcraft.hub.api.BigBangHubApi;
   import com.bigbangcraft.hub.api.GameState;
   import com.bigbangcraft.hub.api.InstanceService;
   import org.bukkit.Bukkit;

   // Obtenha a API via ServicesManager do Bukkit
   BigBangHubApi hub = Bukkit.getServicesManager().load(BigBangHubApi.class);
   if (hub != null && hub.instance().isPresent()) {
       InstanceService instance = hub.instance().get();

       // Atualizar estado quando a partida iniciar
       instance.setState(GameState.IN_GAME);
       instance.setAcceptingPlayers(false);

       // Atualizar capacidade customizada
       instance.updateCapacity(currentPlayers, maxAllowed);
   }
   ```
   Toda chamada a `setState()`, `setAcceptingPlayers()` ou `updateCapacity()` sincroniza instantaneamente com o Velocity via mensagens de plugin na conexão ativa.

---

## 7. Relação com o Ciclo de Vida de Partidas (Match Lifecycle)

Uma instância de minigame executa sessões de partidas controladas pelo contrato `MatchHandle` (BigBangHub 0.3.0).
Quando a partida encerra (`FINISHED` ou `ABORTED`), os jogadores são retornados com segurança ao Hub e a instância entra na fase de limpeza da arena.

Para evitar que novos jogadores entrem enquanto blocos ou entidades ainda estão sendo restaurados:
1. A instância permanece vinculada à partida anterior no proxy, bloqueada para novas admissões.
2. O plugin de minigame executa a restauração da arena.
3. Ao concluir, chama `match.markReady()`.
4. O envio de `INSTANCE_READY` desassocia a instância da partida encerrada, tornando-a disponível para a próxima sessão.

Para detalhes completos do contrato e exemplos de código, consulte [MATCH_LIFECYCLE.md](MATCH_LIFECYCLE.md) e [MINIGAME_INTEGRATION.md](MINIGAME_INTEGRATION.md).
