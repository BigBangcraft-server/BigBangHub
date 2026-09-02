# Configuração

Paper e Velocity carregam `config.yml`, `menus.yml`, `games.yml`, `servers.yml`
e `messages.yml` da pasta de dados do plugin. O snapshot só troca depois de
validar todos os arquivos.

## Menu e bússola

O formato padrão mantém a configuração do item e do menu no mesmo bloco:

```yaml
compass:
  enabled: true
  slot: 4
  material: COMPASS
  name: '<aqua><bold>Selecionar Minigame</bold></aqua>'
  lore: ['<gray>Escolha onde jogar.</gray>']
  glow: true
  flags: []
  title: '<gold><bold>BigBangCraft</bold></gold>'
  rows: 3
  items:
    campominado:
      slot: 13
      material: TNT
      name: '<yellow><bold>Campo Minado</bold></yellow>'
      lore: ['<gray>Entre em uma partida.</gray>', '', '<green>Clique para jogar</green>']
      action:
        type: QUEUE
        value: campominado
```

O item é identificado pelo `PersistentDataContainer`, não por nome/material.
Slots são únicos e ficam dentro do inventário/menu. Conteúdo MiniMessage e
materiais/flags são compilados ao carregar; clique não lê YAML.

Tipos de ação: `PLAYER_COMMAND`, `CONSOLE_COMMAND`, `SERVER`, `QUEUE`, `CLOSE`,
`MESSAGE` e `SOUND`. `PLAYER_COMMAND` pode apontar para alias configurado, por
exemplo `campominado`, e convergirá para `QUEUE`. `CONSOLE_COMMAND` exige
`allow-console-commands: true` e o primeiro comando na allowlist; está desligado
por padrão.

## Jogos e servidores

```yaml
games:
  campominado:
    display-name: Campo Minado
    enabled: true
    queue:
      enabled: true
      min-players: 2
      max-players: 10
      strategy: FILL_WAITING
```

```yaml
servers:
  campominado:
    game: campominado
    host: 10.8.0.2
    port: 25567
    state: WAITING
    player-count: 0
    max-players: 10
```

`host` e `port` só são consumidos pelo Velocity para registrar o destino; nunca
são aceitos no payload do cliente. Os defaults usam os três servidores reais:
`bedwars:25566`, `campominado:25567` e `hg:25568` em `10.8.0.2`.

## Aliases, proteção e inventário

```yaml
aliases:
  campominado: campominado
inventory:
  clear-on-join: true
  lock-lobby-items: true
  prevent-drop: true
  prevent-move: true
protection:
  block-break: true
  block-place: true
  item-drop: true
  item-pickup: true
  damage: true
  pvp: true
  hunger: true
  inventory-manipulation: true
  void-safety: true
```

As demais flags de proteção (`mob-interactions`, `crafting`, `weather`,
`farmland-trampling`, `armor-stand-interaction`, `entity-interaction`,
`bucket-use`, `fire`, `explosions` e `fluid-placement`) seguem o mesmo padrão.
Interação da bússola é tratada antes de filtros de bloco; NPCs e menus não são
cancelados indiscriminadamente.

## Proxy e reload

```yaml
proxy:
  channel: bigbanghub:main
  protocol-version: 1
  hub-server-name: hubminigame
  shared-secret-environment: BIGBANGHUB_MESSAGE_SECRET
  require-hmac: false
  max-payload-bytes: 16384
```

`/bbhub reload` recarrega menus, jogos, servidores, mensagens e flags sem limpar
filas. Canal, versão, limite e autenticação são startup-only; alteração deles é
rejeitada com mensagem de restart. Erros como `Unknown action type: SERVRE` ou
slot/game/ID inválido apontam o caminho do arquivo e preservam o snapshot anterior.
