# Protocolo Paper ↔ Velocity (BigBangHub 0.3.0)

- **Canal Minecraft**: `bigbanghub:main`.
- **Versão**: `1` e `2`.
- **Transporte**: Plugin messaging nativo via Bungee plugin message channel.
- **Limite padrão de payload**: 16 KiB (enforced rigidamente pelo codec).

---

## 1. Envelope Binário

Todos os números inteiros usam big-endian (`DataInputStream` / `DataOutputStream`):

```text
magic             int       0x42424831 (ASCII "BBH1")
protocolVersion   u8        1 ou 2
messageType       u8        código da mensagem (1..23)
correlationId     16 bytes  UUID (dois longs em sequência)
payloadLength     int       0..16384
payload           bytes     dados da mensagem
signatureLength   u16       0 ou 32
signature         bytes     HMAC-SHA256 do cabeçalho até o fim do payload
```

A assinatura HMAC-SHA256 é calculada caso `BIGBANGHUB_MESSAGE_SECRET` esteja configurado no ambiente. Com `require-hmac: true`, mensagens sem assinatura ou com assinatura divergente são descartadas imediatamente com `ProtocolValidationException`.

---

## 2. Catálogo de Mensagens

| Código | Identificador | Origem → Destino | Payload |
|---:|---|:---:|---|
| 1 | `QUEUE_JOIN` | Paper → Velocity | UUID jogador, String gameId |
| 2 | `QUEUE_LEAVE` | Paper → Velocity | UUID jogador |
| 3 | `QUEUE_STATUS` | Paper → Velocity | UUID jogador |
| 4 | `SERVER_CONNECT` | Paper → Velocity | UUID jogador, String serverId |
| 5 | `QUEUE_RESPONSE` | Velocity → Paper | UUID jogador, u8 código, Bool temJogo, String jogo, Int posição, Int tamanho, String mensagem |
| 6 | `SERVER_RESPONSE` | Velocity → Paper | UUID jogador, Bool sucesso, String mensagem |
| 7 | `SERVER_STATUS` | Paper → Velocity | String serverId, u8 estado, Int jogadores, Int maxJogadores |
| 8 | `INSTANCE_REGISTER` | Minigame → Velocity | String instanceId, String gameId, String serverName, UUID sessionId, u8 estado, Int jogadores, Int min, Int max, Bool aceitaJogadores |
| 9 | `INSTANCE_HEARTBEAT` | Minigame → Velocity | String instanceId, UUID sessionId, u8 estado, Int jogadores, Int max, Bool aceitaJogadores |
| 10 | `INSTANCE_UNREGISTER` | Minigame → Velocity | String instanceId, UUID sessionId, String motivo |
| 11 | `INSTANCE_STATE_CHANGE` | Minigame → Velocity | String instanceId, UUID sessionId, u8 estado, Bool aceitaJogadores, Int jogadores, Int max |
| 12 | `INSTANCE_REGISTER_ACK` | Velocity → Minigame | String instanceId, UUID sessionId, Bool sucesso, String mensagem |
| 13 | `MATCH_CREATE` | Minigame → Velocity | String instanceId, UUID sessionId, String matchId, String gameId, Int min, Int max, Bool allowLateJoin, String arenaId |
| 14 | `MATCH_CREATE_ACK` | Velocity → Minigame | String matchId, Bool sucesso, Long revision, String mensagem |
| 15 | `MATCH_STATE_CHANGE` | Minigame → Velocity | String instanceId, UUID sessionId, String matchId, Long revision, u8 estado |
| 16 | `MATCH_STATE_ACK` | Velocity → Minigame | String matchId, Long revision, u8 estado, Bool sucesso, String mensagem |
| 17 | `ADMISSION_REQUEST` | Minigame → Velocity | UUID ticketId, UUID playerId, String matchId, String instanceId, String token |
| 18 | `ADMISSION_RESPONSE` | Velocity → Minigame | UUID ticketId, UUID playerId, String matchId, Bool aceito, u8 papel, String motivo |
| 19 | `PARTICIPANT_STATE_CHANGE` | Minigame → Velocity | String matchId, UUID playerId, u8 papel, u8 estado |
| 20 | `MATCH_FINISH` | Minigame → Velocity | String instanceId, UUID sessionId, String matchId, Long revision, u8 resultado, Long duracaoMs, u16 numVencedores, [UUID vencedores], String metadados |
| 21 | `MATCH_ABORT` | Minigame → Velocity | String instanceId, UUID sessionId, String matchId, Long revision, String motivo |
| 22 | `INSTANCE_READY` | Minigame → Velocity | String instanceId, UUID sessionId, String matchId |
| 23 | `PLAYER_RETURN` | Minigame/Velocity | UUID playerId, u8 motivo, String mensagem |

---

## 3. Enums no Wire

### Estado da Partida (`MatchStateWire`)
- `0`: `CREATED`
- `1`: `WAITING`
- `2`: `COUNTDOWN`
- `3`: `LOCKED`
- `4`: `IN_GAME`
- `5`: `ENDING`
- `6`: `FINISHED`
- `7`: `ABORTED`

### Papel do Participante (`ParticipantRoleWire`)
- `0`: `PLAYER`
- `1`: `SPECTATOR`

### Estado do Participante (`ParticipantStateWire`)
- `0`: `RESERVED`
- `1`: `ADMITTED`
- `2`: `ACTIVE`
- `3`: `ELIMINATED`
- `4`: `SPECTATING`
- `5`: `LEAVING`
- `6`: `LEFT`
- `7`: `DISCONNECTED`

### Resultado da Partida (`MatchResultOutcomeWire`)
- `0`: `WIN`
- `1`: `DRAW`
- `2`: `ABORTED`

### Motivo de Retorno (`ReturnReasonWire`)
- `0`: `MATCH_FINISHED`
- `1`: `MATCH_ABORTED`
- `2`: `PLAYER_ELIMINATED`
- `3`: `PLAYER_LEFT`
- `4`: `SERVER_FAILURE`
- `5`: `ADMIN_FORCE_RETURN`
- `6`: `DIRECT_JOIN_REJECTED`

---

## 4. Validações e Sanity Checks

1. **Capacidade**: `playerCount >= 0`, `minPlayers >= 0`, `maxPlayers >= 1`, `maxPlayers <= 1000`, `minPlayers <= maxPlayers`. Qualquer pacote fora desses limites resulta em falha de decodificação.
2. **Strings**: Strings possuem prefixo UTF de comprimento u16; IDs até 64 bytes; motivos e mensagens até 256 bytes; metadados até 1024 bytes.
3. **Session ID e Match ID**: Validação cruzada estrita entre sessão registrada no proxy e backend que emitiu o pacote.
4. **Rate Limiting**:
   - Por jogador: máximo de 1 requisição a cada 100 ms.
   - Por backend: máximo de 50 mensagens por segundo por servidor backend.
