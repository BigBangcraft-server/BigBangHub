# Arquitetura

```text
Player
  -> Velocity + BigBangHub Velocity
  -> Hub Paper + BigBangHub Paper
  -> Compass / NPC alias / commands
  -> versioned plugin message
  -> QueueService
  -> RoutingService
  -> selected RegisteredServer
  -> minigame backend
```

## Limites

`bigbanghub-api` contém somente contratos, identificadores validados, estados e
resultados. `bigbanghub-common` não importa Bukkit nem Velocity: mantém a fila,
o algoritmo de roteamento, o parser de configuração e o protocolo binário.
`bigbanghub-paper` é dono da experiência no hub e nunca escolhe IP/porta.
`bigbanghub-velocity` é dono da fila e valida qualquer destino antes de conectar.

O plugin não conhece regras de BedWars, Campo Minado ou HG. Os IDs atuais vêm da
rede real: `bedwars`, `campominado` e `hg`; o documento de infraestrutura usa
`campominado`, sem hífen.

## Topologia real considerada

- Internet -> ubuntu2, Velocity 4.1.1 em `10.8.0.1:25565`.
- WireGuard -> brainiac em `10.8.0.2`.
- Hub Paper 26.2-120 em `10.8.0.2:25565`.
- BedWars, diretório `bedward`, em `25566`.
- Campo Minado em `25567`.
- HG em `25568`.
- Java 25; Xmx documentado: hub 1536M, BedWars 2048M, Campo Minado 2048M, HG 3072M, Velocity 2G.

O projeto não introduz Redis, SQL, broker, autoscaling ou outro coordenador.
As filas vivem no único proxy documentado; o ciclo de vida é conexão,
transferência ou disconnect.

## Fluxo de fila

1. O Paper compila a configuração ao carregar e guarda templates de menu,
   componentes MiniMessage e ações já validadas.
2. Compass, alias e `/queue join` chamam o mesmo `QueueService` Paper.
3. O Paper envia um envelope `QUEUE_JOIN`; o proxy aceita somente mensagens
   vindas do backend configurado como `hubminigame`.
4. O proxy valida jogador, jogo e capacidade, registra a entrada de forma
   atômica e escolhe uma instância `WAITING` elegível.
5. Reserva uma vaga, remove o jogador da fila e inicia `createConnectionRequest`.
   Em falha, libera a reserva e recoloca o jogador na fila.
6. Disconnect remove a associação no proxy.

NPCs FancyNpcs podem chamar o alias (`campominado`) ou outra operação equivalente
configurada. A configuração existente da rede usa `send_to_server`, que é uma
transferência direta; para convergir com a fila, troque a ação do NPC em uma
janela controlada para uma ação de comando/alias. BigBangHub não reinventa NPCs.

## Roteamento e estados

`FILL_WAITING` considera apenas servidores do jogo correto, habilitados,
`WAITING` e com capacidade. A instância com mais jogadores é escolhida para
preencher partidas; empate usa o ID lexicograficamente menor. Reservas atômicas
evitam overbooking entre chamadas concorrentes. Os estados suportados são
`OFFLINE`, `STARTING`, `WAITING`, `STARTING_GAME`, `IN_GAME`, `ENDING`, `FULL` e
`MAINTENANCE`.

O registro aceita atualizações `SERVER_STATUS` de um backend cujo nome coincide
com o ID do servidor. Os minigames atuais ainda não enviam esse heartbeat, então
os valores iniciais de `servers.yml` são a capacidade operacional conhecida.

## Reload e threads

Reload faz `load -> validate -> construct -> atomic swap`. Falha mantém o
snapshot antigo e nunca limpa `InMemoryQueueService`. Canal, versão, limite de
payload e autenticação exigem restart para não deixar Paper e Velocity em
protocolos diferentes.

Operações de fila são curtas e sem I/O. A implementação usa uma única trava
curta para manter a associação global jogador/fila atômica; não há lock durante
transferência ou rede. Callbacks de API executam no thread do chamador da
implementação. Paper agenda mensagens ao jogador no thread principal; Velocity
não bloqueia seu event loop com disco ou rede síncrona.
