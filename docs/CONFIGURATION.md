# Configuração (BigBangHub 0.3.0)

O BigBangHub carrega `config.yml`, `menus.yml`, `games.yml`, `servers.yml` e `messages.yml` da pasta de dados do plugin tanto no Paper quanto no Velocity.

---

## 1. Papel do Servidor (`server.role`)

No Paper, defina a responsabilidade do processo:

```yaml
server:
  role: HUB # Opções: HUB, MINIGAME, GENERIC
```

### Configuração de Minigame Agent (`role: MINIGAME`):
Quando um servidor roda como minigame (ex: Campo Minado, BedWars, HG), configure o bloco `instance`:

```yaml
server:
  role: MINIGAME
  instance:
    instance-id: campominado-01
    game-id: campominado
    server-name: campominado-01 # Nome correspondente no Velocity
    heartbeat:
      interval: 3s
    capacity:
      min-players: 2
      max-players: 10
    accepting-players: true
```

*Nota: Em servidores com papel `MINIGAME`, o plugin não registra proteções de lobby nem a bússola de navegação, dedicando-se exclusivamente ao ciclo de vida da instância e das partidas.*

---

## 2. Ciclo de Vida de Partidas e Espectadores (`match` e `spectator`)

Configurações introduzidas no BigBangHub 0.3.0 presentes no `config.yml` (Velocity e Paper):

```yaml
match:
  admission-timeout: 10s   # TTL do ticket criptográfico de admissão de jogadores
  return-timeout: 10s      # Tempo limite para transferências de retorno ao Hub
  finished-retention: 60s  # Retenção de tombstones de partidas encerradas para consulta
  auto-create-match: true  # Se true, cria e abre partidas automaticamente no boot e pós-cleanup
  reconnect-timeout: 60s   # Janela de tolerância para desconexão e recuperação de sessão
  auto-reconnect: true     # Reconecta automaticamente à partida ao reentrar no Hub
  post-match-timeout: 15s  # Janela de decisão pós-jogo para /playagain e /rematch antes de retorno ao Hub

spectator:
  enabled: true            # Permite o ingresso e transição de jogadores para espectadores
```

---

## 3. Configuração de Registro e Roteamento no Velocity

No `config.yml` do Velocity:

```yaml
registry:
  heartbeat-timeout: 10s   # Tempo para degradar para UNAVAILABLE
  suspect-threshold: 5s    # Tempo para degradar para SUSPECT
  fallback-to-hub: true    # Redirecionar jogadores ao Hub em caso de queda do minigame
  allowed:
    "campominado-*":
      game-id: campominado
    "bedwars-*":
      game-id: bedwars
    "hg-*":
      game-id: hg

routing:
  reservation-ttl: 10s     # Tempo limite para expiração de reservas não confirmadas

proxy:
  channel: bigbanghub:main
  protocol-version: 1
  hub-server-name: hubminigame
  shared-secret-environment: BIGBANGHUB_MESSAGE_SECRET
  require-hmac: false
  max-payload-bytes: 16384
```

---

## 4. Jogos e Estratégias de Roteamento (`games.yml`)

As estratégias de matchmaking disponíveis para cada minigame são:
- `FILL_EXISTING_MATCH` / `FILL_WAITING` (Padrão): Preenche partidas que já estão esperando por jogadores com capacidade antes de abrir novas instâncias.
- `LEAST_PLAYERS`: Distribui a carga entre as instâncias disponíveis.
- `ROUND_ROBIN`: Alterna ciclicamente entre as instâncias elegíveis.

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

  bedwars:
    display-name: BedWars
    enabled: true
    queue:
      enabled: true
      min-players: 4
      max-players: 16
      strategy: FILL_WAITING
```

---

## 5. Servidores Estáticos de Fallback (`servers.yml`)

Utilizados como bootstrap e para retrocompatibilidade quando nenhum agente dinâmico ainda se registrou:

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

---

## 6. Bússola e Menus (`menus.yml`)

Utilizado no servidor com papel `role: HUB`:

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

---

## 7. Proteções do Lobby e Inventário

Ativas no `HUB` para preservar o spawn contra quebras, danos, fome e quedas no void:

```yaml
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
  mob-interactions: true
  crafting: true
  inventory-manipulation: true
  weather: true
  farmland-trampling: true
  armor-stand-interaction: true
  entity-interaction: true
  bucket-use: true
  fire: true
  explosions: true
  fluid-placement: true
  void-safety: true
```
