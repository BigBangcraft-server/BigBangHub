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
