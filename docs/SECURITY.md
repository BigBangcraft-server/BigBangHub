# Segurança

## Fronteiras de confiança

O cliente é não confiável. Paper é um backend confiável somente quando a conexão
Velocity identifica `hubminigame`; outros backends são aceitos apenas para
`SERVER_STATUS` com o próprio server ID. Velocity é a autoridade para destinos,
filas e transferências. BungeeGuard, segredo de forwarding e firewall WireGuard
protegem a ligação de rede conforme a arquitetura da BigBangCraft.

## Permissões

| Permissão | Default | Uso |
|---|---|---|
| `bigbanghub.admin` | OP | status administrativo |
| `bigbanghub.reload` | OP | reload transacional |
| `bigbanghub.compass` | todos | `/bbhub compass` |
| `bigbanghub.queue.join` | todos | entrar na fila/alias |
| `bigbanghub.queue.leave` | todos | sair |
| `bigbanghub.queue.status` | todos | consultar |
| `bigbanghub.server.connect` | todos | contrato para conexão direta |
| `bigbanghub.bypass.protection` | OP | bypass de proteção |
| `bigbanghub.bypass.inventory` | OP | bypass de inventário |

OP defaults são explícitos no `plugin.yml`; `isOp()` não é usado como lógica
de segurança. A configuração real de LuckPerms deve continuar sem permissões
para o grupo default e sem `velocity.command.server` para jogadores.

## Ações e configuração

Jogadores não escrevem configuração. `CONSOLE_COMMAND` é opt-in, exige allowlist
do primeiro token e só substitui `{player}` e `{uuid}`. Não há concatenação de
dados arbitrários do jogador em um comando privilegiado. Configuração inválida
falha antes do swap e não altera estado de fila.

## Mensagens internas

O codec valida magic, versão exatamente 1, tipo, correlation ID, tamanho total,
payload, IDs, estados, capacidade e ausência de bytes extras. HMAC-SHA256 com
comparação constante é suportado via `BIGBANGHUB_MESSAGE_SECRET`; segredo ausente
quando obrigatório desabilita o plugin com erro. Mensagens de versão futura,
assinatura inválida, truncadas, oversized, duplicadas ou de backend incorreto
são rejeitadas. Um limite de 100 ms por jogador reduz spam; disconnect limpa os
mapas de rate limit, transferência e fila.

O segredo nunca é logado. IDs de servidor enviados pelo Paper são comparados com
o registry confiável do Velocity; o cliente jamais escolhe host/porta.

## Disponibilidade e limites conhecidos

Falha de conexão libera a reserva e recoloca o jogador na fila; erro comum não
expulsa o jogador. Filas são memória do único proxy: restart perde estado. Os
valores de capacidade ficam corretos quando backends reportam `SERVER_STATUS`;
os minigames atuais ainda não implementam esse heartbeat, então `servers.yml`
precisa ser atualizado/recarregado durante a operação. Não existe sincronização
entre múltiplos proxies, persistência, autoscaling ou matchmaking por ELO neste
release.
