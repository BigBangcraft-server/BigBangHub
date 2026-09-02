# Protocolo Paper ↔ Velocity (BigBangHub 0.2.0)

- **Canal Minecraft**: `bigbanghub:main`.
- **Versão**: `1`.
- **Transporte**: Plugin messaging nativo via Bungee plugin message channel.
- **Limite padrão de payload**: 16 KiB (enforced rigidamente pelo codec).

---

## 1. Envelope Binário

Todos os números inteiros usam big-endian (`DataInputStream` / `DataOutputStream`):

```text
magic             int       0x42424831 (ASCII "BBH1")
protocolVersion   u8        1
messageType       u8        código da mensagem (1..12)
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

---

## 3. Estados no Wire (`GameStateWire`)

O byte de estado segue o enum ordinal:
- `0`: `OFFLINE`
- `1`: `STARTING`
- `2`: `WAITING` (Elegível para matchmaking e reservas)
- `3`: `STARTING_GAME`
- `4`: `IN_GAME`
- `5`: `ENDING`
- `6`: `FULL`
- `7`: `MAINTENANCE`

---

## 4. Validações e Sanity Checks

1. **Capacidade**: `playerCount >= 0`, `minPlayers >= 0`, `maxPlayers >= 1`, `maxPlayers <= 1000`, `minPlayers <= maxPlayers`. Qualquer pacote fora desses limites resulta em falha de decodificação.
2. **Strings**: Strings possuem prefixo UTF de comprimento u16; o comprimento máximo para IDs e nomes de servidor é de 64 bytes; motivos e mensagens de resposta até 256 bytes.
3. **Session ID**: Cada payload de ciclo de vida de instância (8 a 12) contém o `UUID sessionId` do runtime atual. O Velocity valida se o `sessionId` corresponde à sessão registrada ativa no nó, descartando silenciosamente qualquer mensagem remanescente de execuções passadas.
4. **Rate Limiting**:
   - Por jogador: máximo de 1 requisição a cada 100 ms.
   - Por backend: máximo de 50 mensagens por segundo por servidor backend.
