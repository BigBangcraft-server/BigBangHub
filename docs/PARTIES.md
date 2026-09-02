# Domínio e Ciclo de Vida de Parties (BigBangHub 0.4.0)

O BigBangHub 0.4.0 introduz o suporte nativo e autoritativo a **Parties (Grupos de Jogadores)** na rede BigBangCraft.
O sistema foi projetado para permitir que amigos permaneçam agrupados, entrem juntos em filas de minigames, sejam admitidos atomicamente na mesma partida e retornem ao Hub mantendo sua coesão social.

---

## 1. Identidade e Invariantes Fundamentais

### Identidade Própria (`PartyId`)
- A Party possui sua própria identidade única (`PartyId`), gerada aleatoriamente via UUID.
- O UUID do líder **nunca** é utilizado como identificador da Party.
- Isso assegura que a Party sobreviva perfeitamente a transferências de liderança, desconexões e reconexões do líder.

### Invariantes Estritas de Domínio
1. **Unicidade de Participação**: Um jogador pertence a no máximo **uma party** em toda a rede (`1 player <= 1 party`).
2. **Liderança Única e Pertencente**: A party possui exatamente **um líder**, e o líder obrigatoriamente pertence à lista de membros.
3. **Não-Vacuidade**: Parties vazias não existem. Ao perder seu último membro ou quando um líder solitário sai, a party é imediatamente desfeita (`DISBANDING`).
4. **Auto-Convite Proibido**: Um jogador não pode convidar a si mesmo (`CANNOT_INVITE_SELF`).
5. **Ingressos e Convites Válidos**: Convites expirados não podem ser aceitos (`INVITE_EXPIRED`).
6. **Capacidade Máxima Delimitada**: O tamanho da party respeita estritamente o limite global configurado (`party.max-size`, padrão 8).
7. **Single-Use e Invalidação**: Convites são de uso único. Ao aceitar um convite para uma party, todos os demais convites pendentes daquele jogador em outras parties são automaticamente invalidados.
8. **Concorrência Segura**: Tentativas concorrentes de adicionar o mesmo jogador (ex: 100 parties disputando o mesmo jogador) são sincronizadas de forma que exatamente uma consiga adicioná-lo e as demais recebam `PLAYER_ALREADY_IN_PARTY`.

---

## 2. Estados da Party (`PartyState`)

O modelo de estados da Party é explícito e previne flags booleanas dispersas:

```text
       ┌──────────────┐
       │     IDLE     │◄──────────────────┐
       └──────┬───────┘                   │
              │ (Join Queue)              │ (Match Finished /
              ▼                           │  Queue Left)
       ┌──────────────┐                   │
       │    QUEUED    │                   │
       └──────┬───────┘                   │
              │ (Capacity Reserved)       │
              ▼                           │
       ┌──────────────┐                   │
       │   ASSIGNED   │                   │
       └──────┬───────┘                   │
              │ (Admission Handshake)     │
              ▼                           │
       ┌──────────────┐                   │
       │   IN_MATCH   │───────────────────┘
       └──────┬───────┘
              │ (Disband)
              ▼
       ┌──────────────┐
       │  DISBANDING  │
       └──────────────┘
```

- `IDLE`: Party livre no Hub ou sem atividade de partida. Permite convites, expulsões, saídas e transferências de liderança.
- `QUEUED`: Party na fila global de um minigame como unidade indivisível. Mutações estruturais são bloqueadas (`PARTY_MUTATION_LOCKED`).
- `ASSIGNED`: Vagas reservadas atomicamente em uma instância de minigame. Tickets de admissão em trânsito.
- `IN_MATCH`: Todos os membros da party estão participando da mesma sessão de jogo (`MatchSession`).
- `DISBANDING`: Estado terminal durante encerramento da party.

---

## 3. Estrutura de Domínio e Snapshots

### Papéis (`PartyRole`)
- `LEADER`: Líder do grupo. Possui autoridade para convidar, expulsar membros, transferir liderança, desfazer a party e colocar o grupo na fila de minigames.
- `MEMBER`: Membro regular da party. Pode visualizar membros e sair voluntariamente.

### Convite (`PartyInvite`)
- `partyId`: Identificador da party expedidora.
- `inviter`: UUID do líder que enviou o convite.
- `target`: UUID do jogador convidado.
- `createdAt` e `expiresAt`: Janela de validade (TTL padrão de 60s).
- `isExpired(Instant now)`: Validação temporal determinística.

### Snapshot Imutável (`PartySnapshot`)
Representação imutável com revisão monotônica (`revision`):
```java
public record PartySnapshot(
    PartyId partyId,
    UUID leader,
    Map<UUID, PartyMember> members,
    Map<UUID, PartyInvite> invitedPlayers,
    PartyState state,
    Instant createdAt,
    long revision
)
```

---

## 4. Política de Desconexão e Tolerância a Falhas

O BigBangHub implementa uma política robusta para desconexões inesperadas:

1. **Desconexão do Líder**:
   - Quando o líder desconecta da rede, entra em vigor uma janela de tolerância (`leader-disconnect-grace`, padrão 30 segundos).
   - Se o líder reconectar dentro da janela, a party mantém seu estado normalmente.
   - Se a janela expirar:
     - Caso existam outros membros: a liderança é **automaticamente transferida** para o membro elegível mais antigo (ordem de entrada). O líder desconectado é removido e o evento `PartyLeaderChangedEvent` é publicado.
     - Caso o líder estivesse sozinho: a party é automaticamente desfeita (`PartyDisbandedEvent`).
2. **Desconexão de Membro Comum**:
   - Membros comuns desconectados têm tolerância de 30 segundos para retornar. Após a expiração, são removidos da party sem prejudicar os demais participantes.

---

## 5. Configuração YAML (`config.yml`)

No arquivo `config.yml` (tanto no Proxy Velocity quanto no Paper):

```yaml
party:
  max-size: 8                     # Limite máximo de jogadores por grupo
  invite-ttl: 60s                 # Tempo de validade do convite
  leader-disconnect-grace: 30s    # Tolerância de desconexão antes da troca de líder
  invite-cooldown: 5s             # Cooldown contra spam de convites
```

---

## 6. API Pública (`PartyService`)

Disponível através de `BigBangHubApi.parties()`:

```java
PartyService parties = hub.parties();

// Criar nova party
PartySnapshot party = parties.createParty(leaderUuid);

// Convidar jogador
PartyInvite invite = parties.invitePlayer(leaderUuid, targetUuid);

// Aceitar convite
PartySnapshot updated = parties.acceptInvite(targetUuid, party.partyId());

// Recusar convite
parties.declineInvite(targetUuid, party.partyId());

// Sair da party
PartySnapshot remaining = parties.leaveParty(memberUuid);

// Expulsar membro
PartySnapshot afterKick = parties.kickPlayer(leaderUuid, memberUuid);

// Transferir liderança
PartySnapshot newLeader = parties.transferLeadership(leaderUuid, memberUuid);

// Consultas em O(1)
Optional<PartySnapshot> myParty = parties.partyOf(playerUuid);
Optional<PartySnapshot> byId = parties.party(partyId);
Set<UUID> members = parties.members(partyId);
```

### Exceções Explícitas (`PartyException`)
Erros de operação lançam `PartyException` contendo `ErrorCode`:
- `PARTY_NOT_FOUND`
- `PLAYER_ALREADY_IN_PARTY`
- `PLAYER_NOT_IN_PARTY`
- `NOT_PARTY_LEADER`
- `PARTY_FULL`
- `CANNOT_INVITE_SELF`
- `TARGET_ALREADY_IN_PARTY`
- `INVITE_ALREADY_PENDING`
- `INVITE_NOT_FOUND`
- `INVITE_EXPIRED`
- `TARGET_NOT_IN_PARTY`
- `CANNOT_KICK_LEADER`
- `CANNOT_TRANSFER_TO_SELF`
- `INVALID_PARTY_STATE`
- `PARTY_MUTATION_LOCKED`
- `RATE_LIMITED`

---

## 7. Comandos e Permissões (`/party`, `/p`)

O sistema fornece comandos universais registrados tanto no Proxy Velocity quanto nos servidores Paper. Quando o jogador está conectado à rede, os comandos são executados com autoridade primária no Velocity.

### Subcomandos Disponíveis

| Comando | Descrição | Permissão Padrão |
| :--- | :--- | :--- |
| `/party` ou `/p` | Exibe o status da party atual (Líder, jogadores e estado). | `bigbanghub.party.use` (true) |
| `/party invite <jogador>` | Convida um jogador. Cria a party automaticamente se o líder ainda não possuir uma. | `bigbanghub.party.invite` (true) |
| `/party accept [jogador\|partyId]` | Aceita o convite pendente. Se omitido, aceita o convite pendente mais recente. | `bigbanghub.party.use` (true) |
| `/party decline [jogador\|partyId]` | Recusa um convite pendente de party. | `bigbanghub.party.use` (true) |
| `/party leave` | Sai voluntariamente da party atual. | `bigbanghub.party.use` (true) |
| `/party kick <jogador>` | Expulsa um membro do grupo (apenas líder). | `bigbanghub.party.invite` (true) |
| `/party leader <jogador>` | Transfere a liderança do grupo para outro membro (apenas líder). | `bigbanghub.party.invite` (true) |
| `/party disband` | Desfaz a party completamente (apenas líder). | `bigbanghub.party.invite` (true) |
| `/party list` | Lista todos os membros atuais e seus respectivos papéis. | `bigbanghub.party.use` (true) |

### Notificações e Componentes Adventure Clicáveis

Ao convidar um jogador, o destinatário recebe uma mensagem interativa via Adventure:

```text
§b§m----------------------------------------
§ePedro §7convidou você para uma Party!
 [ACEITAR]  [RECUSAR]
§b§m----------------------------------------
```

- **`[ACEITAR]`**: Botão verde em negrito com `ClickEvent.runCommand("/party accept Pedro")` e hover text descritivo.
- **`[RECUSAR]`**: Botão vermelho em negrito com `ClickEvent.runCommand("/party decline Pedro")` e hover text descritivo.
- Nenhuma string fornecida pelo usuário é executada como comando arbitrário; o payload é sanitizado e validado pelo protocolo do BigBangHub.

---

## 8. Sincronização Cross-Server e Mensageria

A integridade do grupo independe do servidor Paper onde os jogadores estão situados. Se um líder estiver no Hub e convidar um jogador em um minigame, a party é mantida no Proxy Velocity e sincronizada de ponta a ponta.

### Mensagens de Protocolo `BBH1` (`bigbanghub:main`)

| Código | Tipo | Descrição |
| :---: | :--- | :--- |
| `24` | `PARTY_CREATE` | Criação de party iniciada pelo cliente Paper. |
| `25` | `PARTY_INVITE` | Envio de convite para outro jogador. |
| `26` | `PARTY_ACCEPT` | Aceite de convite pendente. |
| `27` | `PARTY_DECLINE` | Recusa de convite pendente. |
| `28` | `PARTY_LEAVE` | Saída voluntária do jogador. |
| `29` | `PARTY_KICK` | Expulsão de membro pelo líder. |
| `30` | `PARTY_LEADER_CHANGE` | Transferência de liderança de party. |
| `31` | `PARTY_DISBAND` | Desmanche da party pelo líder. |
| `32` | `PARTY_SYNC` | Sincronização do estado e membros da party. |
| `33` | `PARTY_RESPONSE` | Resposta com status, mensagem amigável e `PartyId`. |

---

## 9. Proteções e Segurança

1. **Anti-Spam de Convites**: Cooldown temporal configurável (`party.invite-cooldown`, padrão de 5s) por jogador para prevenir flood de convites a outros usuários.
2. **Rejeição de Falsa Identidade**: Requisições de plugin messaging onde o UUID do cabeçalho diverge do UUID do jogador na conexão de rede são rejeitadas com erro de segurança.
3. **Imutabilidade em Fila e Partida**: Quando uma party está em estado `QUEUED`, `ASSIGNED` ou `IN_MATCH`, modificações estruturais (convidar, expulsar, transferir líder, sair) são rejeitadas com `PARTY_MUTATION_LOCKED`.
4. **Varredura Periódica Centralizada**: A limpeza de convites expirados e lideranças abandonadas é executada na tarefa de sweep periódica do proxy, sem introduzir threads ou timers por jogador.

---

## 10. Filas de Grupo e Matchmaking Atômico

O BigBangHub 0.4.0 garante coesão social absoluta em partidas multiplayer através de matchmaking atômico para parties:

### Entrada e Saída da Fila
1. **Autoridade do Líder**: Apenas o líder da party pode colocar o grupo na fila (`/queue join <game>`) ou retirá-lo (`/queue leave`). Comandos de fila executados por membros comuns são rejeitados com feedback claro.
2. **Notificações Sincronizadas**: Ao entrar ou sair da fila, todos os membros conectados da party recebem avisos imediatos.
3. **Consulta de Status Delegada**: Quando um membro executa `/queue status`, o sistema consulta a posição e status da fila associados ao líder da party.
4. **Resiliência a Desconexões na Fila**: Caso qualquer membro da party desconecte enquanto o grupo estiver em `QUEUED`, a party é imediatamente removida da fila e retorna a `IDLE`, alertando os membros restantes.

### Invariantes do Matchmaking Atômico
1. **Indivisibilidade (No-Split)**: Uma party de tamanho $N$ é tratada como uma entidade atômica. Se uma instância ou partida possui vagas disponíveis menores que $N$, a party **não é dividida**. O despachador busca outra instância apta ou aguarda vagas suficientes.
2. **Reserva All-or-Nothing e Rollback**: A reserva de capacidade no servidor de destino é estritamente atômica. Se qualquer membro do grupo falhar ao reservar slot (por timeout ou contenção concorrente), **todas as reservas já feitas para aquela party são canceladas imediatamente**.
3. **Tickets e Encaminhamento Unificado**: Todos os membros recebem `AdmissionTicket` válidos apontando para o mesmo `matchId` e `instanceId`. O grupo transita de `QUEUED` para `ASSIGNED` e todos os membros iniciam a transferência de rede em paralelo.
4. **Transição para Partida**: À medida que os membros são validados e admitidos pelo backend, a party transita para `IN_MATCH`. No encerramento da partida ou retorno ao Hub, a party retorna automaticamente para `IDLE` preservando seus membros.
