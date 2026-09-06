# Configuração (BigBangHub 0.4.4)

O BigBangHub carrega `config.yml`, `menus.yml`, `games.yml`, `servers.yml` e `messages.yml` da pasta de dados do plugin tanto no Paper quanto no Velocity.

---

## 1. Papel do Servidor (`server.role`)

No Paper, defina a responsabilidade do processo:

```yaml
server:
  role: HUB # Opções: HUB, MINIGAME, GENERIC
```

- `HUB`: lobby principal (bússola, menus, proteções, `/queue`).
- `MINIGAME`: backend de minigame com agente de instância + gerenciador de partidas.
  Com `auto-create-match: false` e nenhuma partida aberta, entradas ficam livres
  (gerenciamento externo via API — ver MINIGAME_INTEGRATION §6).
- `GENERIC` (padrão se omitido): sem lobby, sem instância, sem partidas. Entrada
  livre total — o papel para survival, criativo e eventos.

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
  auto-create-match: true  # Se true, cria e abre partidas automaticamente no boot e pós-cleanup.
                           # Se false, o minigame cria via API; sem partida aberta, entradas ficam livres (0.4.5)
  reconnect-timeout: 60s   # Janela de tolerância para desconexão e recuperação de sessão
  auto-reconnect: true     # Reconecta automaticamente à partida ao reentrar no Hub (só crash; saída voluntária abandona desde 0.4.4)
  post-match-timeout: 15s  # Janela de decisão pós-jogo para /playagain e /rematch antes de retorno ao Hub

spectator:
  enabled: true            # Permite o ingresso e transição de jogadores para espectadores
```

> Desde 0.3.0 sem mudanças de chave; em 0.4.x o significado de `auto-reconnect`
> foi refinado: vale para retorno após queda. Saída voluntária (`/leave`, `/hub`,
> `/lobby`, `/server hub`) abandona a partida em vez de segurar slot.

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

> `servers.yml` é bootstrap: após o agente Paper registrar via `INSTANCE_REGISTER`,
> o registro dinâmico prevalece. Não precisa listar cada instância (`-01`, `-02`).

---

## 6. Aliases de entrada: `/campominado`, `/bedwars`, `/hg` (`config.yml`)

Os três aliases abaixo são **obrigatórios** (proxy e Hub Paper). Sem eles, o comando
fica vermelho no Brigadier e o NPC não tem para onde apontar:

```yaml
aliases:
  campominado: campominado
  bedwars: bedwars
  hg: hg
```

Cada alias converge para o mesmo ponto canônico (`QueueService → Routing →
Reservation → AdmissionTicket → Transfer`). NPCs usam `player_command <alias>`.

---

## 7. Comandos de jogador e permissões (0.4.4)

| Comando | O que faz | Permissão (padrão) |
|---|---|:---:|
| `/queue join <game>` | Entra na fila (líder leva a party) | `bigbanghub.queue.join` (true) |
| `/campominado`, `/bedwars`, `/hg` | Alias Brigadier = `queue join` | `bigbanghub.queue.join` (true) |
| `/queue leave` | Sai **da fila** (não da partida) | `bigbanghub.queue.leave` (true) |
| `/queue status` | Posição na fila | `bigbanghub.queue.status` (true) |
| `/leave`, `/hub`, `/lobby`, `/sair` | **Abandona a partida** + volta ao Hub | `bigbanghub.match.leave` (true) |
| `/server hub` | Volta ao Hub; com partida ativa, abandona antes (0.4.4) | (permissão do Velocity) |
| `/reconnect` | Volta à partida em `DISCONNECTED` (só crash) | — |
| `/playagain`, `/again` | Refila no mesmo jogo (pós-jogo) | — |
| `/rematch`, `/revanche` | Vota revanche (pós-jogo) | — |
| Ação `SERVER` em menus | Transferência direta (não usar em menus públicos) | `bigbanghub.server.connect` (**op** desde 0.4.3) |
| `/bbhub ...` | Administração e telemetria | `bigbanghub.admin*` (op) |

> `/queue leave` × `/leave`: o primeiro só tira da fila (por isso "não estou em
> fila" com partida ativa); o segundo abandona partida + fila + volta ao Hub.

---

## 8. Bússola e Menus (`menus.yml`)

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

## 9. Proteções do Lobby e Inventário

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

---

## 10. Mensagens, Efeitos Sonoros e HUD (`messages.yml`)

Permite personalizar títulos, subtítulos, efeitos sonoros (Sound FX) e actionbars tanto no proxy Velocity quanto nos lobbies Paper:

```yaml
messages:
  proxy-unavailable: '<red>Não foi possível localizar um servidor agora.</red>'
  game-unavailable: '<red>Este minigame está temporariamente indisponível.</red>'

experience:
  sound-enabled: true
  title-enabled: true
  actionbar-enabled: true

  match-found:
    title: "§a§lPARTIDA ENCONTRADA!"
    subtitle: "§fConectando ao servidor em instantes..."
    sound: "entity.player.levelup"
    volume: 1.0
    pitch: 1.0

  reconnect-available:
    title: "§e§lPARTIDA EM ANDAMENTO"
    subtitle: "§7Clique no chat ou use §a/reconnect"
    sound: "block.note_block.pling"
    volume: 1.0
    pitch: 1.2

  rematch-consensus:
    title: "§a§lREVANCHE ACEITA!"
    subtitle: "§fPreparando nova rodada..."
    sound: "entity.player.levelup"
    volume: 1.0
    pitch: 1.5

  party-invite-received:
    title: "§b§lCONVITE DE PARTY"
    subtitle: "§f{player} convidou você para o grupo!"
    sound: "entity.experience_orb.pickup"
    volume: 1.0
    pitch: 1.0

  party-disbanded:
    title: "§c§lPARTY DISSOLVIDA"
    subtitle: "§7O líder desfez o grupo."
    sound: "entity.villager.no"
    volume: 1.0
    pitch: 0.8

party-hud:
  actionbar-format: "§eParty: {role} §8| §7Membros: §f{members_online}/{members_total} §8| §7Status: {status}"
  leader-role: "§6★ Líder"
  member-role: "§7• Membro"
  status:
    idle: "§aLobby"
    queued: "§eNa Fila"
    assigned: "§bConectando"
    in-match: "§cEm Partida"
```
