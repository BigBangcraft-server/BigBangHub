# Protocolo Paper ↔ Velocity

- Canal Minecraft: `bigbanghub:main`.
- Versão: `1`.
- Transporte: plugin messaging; Bungee plugin-message channel já está habilitado
  na configuração Velocity real.
- Limite padrão do payload: 16 KiB; o parser YAML limita a configuração a esse
  teto e o SnakeYAML a 256 KiB.

## Envelope binário

Todos os inteiros usam big-endian, conforme `Data{Input,Output}Stream`:

```text
magic             int       0x42424831 (BBH1)
protocolVersion   u8        1
messageType       u8        código conhecido
correlationId     16 bytes  UUID (dois longs)
payloadLength     int       0..16384
payload           bytes
signatureLength   u16       0 ou 32
signature         bytes     HMAC-SHA256 do envelope até payload
```

HMAC é ativado quando `BIGBANGHUB_MESSAGE_SECRET` contém valor; com
`require-hmac: true`, a variável é obrigatória. O segredo não fica no Git, não é
logado e deve ser igual nos dois processos. Se não for usado, BungeeGuard,
firewall e validação da origem do backend continuam sendo a fronteira confiável.

## Mensagens

| Código | Tipo | Payload |
|---:|---|---|
| 1 | `QUEUE_JOIN` | UUID do jogador + game ID |
| 2 | `QUEUE_LEAVE` | UUID do jogador |
| 3 | `QUEUE_STATUS` | UUID do jogador |
| 4 | `SERVER_CONNECT` | UUID do jogador + server ID |
| 5 | `QUEUE_RESPONSE` | UUID, código, jogo opcional, posição, tamanho, mensagem |
| 6 | `SERVER_RESPONSE` | UUID, sucesso, mensagem |
| 7 | `SERVER_STATUS` | server ID, estado, jogadores, capacidade |

Strings têm comprimento u16 e máximo de 256 bytes. IDs passam por `GameId` ou
`ServerId`; estado, código e tipo desconhecidos são rejeitados.

## Validação

Velocity marca o evento como handled, aceita requisições de fila/conexão somente
do `hubminigame`, confirma que o UUID do payload é o jogador da conexão, limita
uma requisição por jogador a cada 100 ms e rejeita duplicação de transferência.
`SERVER_STATUS` só é aceito quando o backend de origem tem o mesmo nome do
servidor reportado. Payload truncado, oversized, magic/versão/tipo desconhecido,
assinatura inválida ou dados à direita não chegam ao domínio.

Mensagens recebidas do proxy no Paper também passam pelo codec e só completam
uma requisição pendente com o `correlationId` correspondente. A resposta expira
em 100 ticks; não há retry automático que possa duplicar transferências.
