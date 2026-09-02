# BigBangHub 0.3.0

Infrastructure e Foundation de Hub/Lobby, Filas Globais, Registro de Instâncias e **Ciclo de Vida Padronizado de Partidas** para a rede **BigBangCraft** (Paper 26.2 e Velocity 4.1.1, Java 25).

A versão `0.3.0` introduz o contrato padronizado de ciclo de vida de partidas da BigBangCraft:
- **Match Lifecycle**: Criação de sessão, abertura para jogadores, contagem regressiva, trava da partida, início in-game, eliminação, transição para espectador, encerramento com resultado, retorno seguro ao Hub e handshake de limpeza (`markReady`).
- **Admission Tickets**: Ingressos criptográficos transitórios de uso único com TTL curto, impedindo entradas diretas sem permissão ou ataques de repetição. Jogadores sem ticket são conduzidos com segurança ao Hub sem bans ou kicks.
- **Invariante de Sessão**: Um jogador só pode pertencer a no máximo uma partida ativa em toda a rede.
- **Orquestração Velocity**: Matchmaking match-aware priorizando preencher partidas abertas (`FILL_EXISTING_MATCH`), comandos administrativos de inspeção e aborto forçado, e telemetria operacional estendida.
- **Zero Middleware Externo**: Não há dependência de Redis, MySQL, RabbitMQ ou Kafka. Todo o cluster opera através do canal de plugin messaging nativo `bigbanghub:main`.

---

## Módulos

- `bigbanghub-api`: Contratos públicos, enums de lifecycle (`MatchState`, `ParticipantRole`, `ParticipantState`, `ReturnReason`, `ServerRole`, `InstanceHealth`), records de snapshot/resultado e interfaces (`MatchManager`, `MatchHandle`, `InstanceRegistry`, `InstanceService`, `QueueService`, `RoutingService`).
- `bigbanghub-common`: Implementações centrais em memória: `InMemoryMatchRegistry`, `MatchStateMachine`, `AdmissionTicketService`, `MatchEventBus`, `InMemoryInstanceRegistry`, `InMemoryReservationService`, `InMemoryQueueService`, codec binário `BBH1` (mensagens 1 a 23) com HMAC opcional.
- `bigbanghub-paper`: Plugin unificado para servidores Paper 26.2. Atua como núcleo do Lobby (`role: HUB`) ou como controlador de partidas e agente (`role: MINIGAME`, com `PaperMatchManager`, validação de tickets na entrada, proteção contra conexão direta e retorno seguro ao Hub).
- `bigbanghub-velocity`: Plugin para proxy Velocity 4.1.1. Orquestrador do cluster de instâncias, partidas globais, emissor e validador de tickets de admissão, filas orientadas a eventos e comandos administrativos.

---

## Requisitos e Build

- **Java 25** (OpenJDK / GraalVM).
- **Gradle 8.14.3** (wrapper incluso).
- Compilado contra `io.papermc.paper:paper-api:26.2.build.121-stable` e `com.velocitypowered:velocity-api:4.1.1`.

```bash
./gradlew clean build --no-daemon
```

Artefatos gerados:

```text
bigbanghub-paper/build/libs/bigbanghub-paper-0.3.0.jar
bigbanghub-velocity/build/libs/bigbanghub-velocity-0.3.0.jar
bigbanghub-api/build/libs/bigbanghub-api-0.3.0.jar
bigbanghub-common/build/libs/bigbanghub-common-0.3.0.jar
```

---

## Comandos

### No Proxy Velocity:
```text
/bbhub status              # Visão geral do cluster, instâncias, partidas e filas
/bbhub instances           # Lista instâncias dinâmicas, saúde e partida ativa vinculada
/bbhub instance <id>       # Detalhes completos de uma instância
/bbhub matches             # Lista todas as partidas ativas na rede e contadores
/bbhub match <id>          # Detalhes da partida, participantes e papéis
/bbhub match <id> abort    # Força aborto de partida travada com retorno ao Hub
/bbhub return <player>     # Retorna forçadamente um jogador ao Hub com auditoria
/bbhub queues              # Status de todas as filas, espera e instâncias elegíveis
/bbhub queue <game>        # Detalhes dos jogadores na fila
/bbhub metrics             # Telemetria acumulada de requisições, transferências e partidas
/bbhub reload              # Recarrega configurações de forma segura
/queue join <game>         # Entrar na fila de um minigame
/queue leave               # Sair da fila
/queue status              # Consultar sua posição atual
```

### No Servidor Paper:
```text
/bbhub status              # Exibe papel do servidor, status da ponte e detalhes da instância
/bbhub compass             # Abre o menu da bússola de minigames (apenas em role HUB)
/bbhub reload              # Recarrega menus e proteções locais
/campominado               # Atalho direto para entrar na fila (configurável em aliases)
```

---

## Documentação Técnica

- [`docs/MATCH_LIFECYCLE.md`](docs/MATCH_LIFECYCLE.md): Especificação completa da máquina de estados de partidas, transições, tickets de admissão e tolerância a falhas.
- [`docs/MINIGAME_INTEGRATION.md`](docs/MINIGAME_INTEGRATION.md): Guia prático passo a passo com exemplos em Java para integração de plugins de minigame.
- [`docs/INSTANCE_LIFECYCLE.md`](docs/INSTANCE_LIFECYCLE.md): Ciclo de vida das instâncias, liveness, heartbeats, sessões, reservas e handshake de limpeza.
- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md): Arquitetura global do cluster, topologia real e garantias de concorrência.
- [`docs/API.md`](docs/API.md): Contratos públicos, interfaces e eventos observacionais.
- [`docs/PROTOCOL.md`](docs/PROTOCOL.md): Especificação dos envelopes binários `BBH1`, catálogo de mensagens (1 a 23) e layouts.
- [`docs/CONFIGURATION.md`](docs/CONFIGURATION.md): Guia de configuração de todos os arquivos YAML (`match`, `spectator`, etc.).
- [`docs/OPERATIONS.md`](docs/OPERATIONS.md): Guia operacional, implantação, permissões e diagnóstico.
- [`docs/SECURITY.md`](docs/SECURITY.md): Segurança de ingressos, proteção contra entrada direta, isolamento e auditoria.
