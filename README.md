# BigBangHub 0.4.0

Infrastructure e Foundation de Hub/Lobby, Filas Globais, Registro de Instâncias, **Ciclo de Vida Padronizado de Partidas**, **Party System**, **Group Matchmaking**, **Reconnect & Session Recovery** e **Rematch & Play Again** para a rede **BigBangCraft** (Paper 26.2 e Velocity 4.1.1, Java 25).

A versão `0.4.0` introduz o subsistema social e de experiência do jogador completo da BigBangCraft:
- **Party System**: Gestão de grupos 100% in-memory com controle de liderança, convites com cooldown e expiração, transferência, expulsão, warp de membros para o mesmo servidor e sucessão automática de líder em caso de desconexão.
- **Group Queue & Matchmaking Atômico**: Parties ingressam em filas de minigames como uma unidade indivisível, alocadas atomicamente em instâncias com capacidade suficiente sem fragmentar o grupo.
- **Party Admission & Match Cohesion**: Ingressos criptográficos `AdmissionTicket` enriquecidos com metadados da party, validação sincronizada no Paper backend e rollback coordenado com retorno ao Hub se algum membro falhar.
- **Reconnect & Session Recovery**: Janela de reconexão configurável para recuperação transparente de sessão após desconexões transitórias, preservando papéis e integridade da partida.
- **Rematch & Play Again**: Sistema pós-partida de votação imediata para revanche com consenso de 100% e re-queue coordenado de toda a party.
- **Player Experience no Hub**: Actionbar HUD periódica (papel, membros e status), efeitos audiovisuais (títulos, subtítulos e sons via Kyori Adventure) e bloqueio de menus de fila para membros não-líderes.
- **Security Hardening**: Sanitização rigorosa de nomes de jogadores (`^[a-zA-Z0-9_]{3,16}$`), rate-limiting contra spam de convites, anti-spoofing em pacotes de rede e recuperação graciosa desbloqueando parties caso instâncias caiam.
- **Zero Middleware Externo**: Não há dependência de Redis, MySQL, RabbitMQ ou Kafka. Todo o cluster opera através do canal de plugin messaging nativo `bigbanghub:main`.

---

## Módulos

- `bigbanghub-api`: Contratos públicos, enums de lifecycle (`MatchState`, `PartyState`, `ParticipantRole`, `ParticipantState`, `ReturnReason`), records (`PartySnapshot`, `PartyInvite`, `MatchSnapshot`) e interfaces (`PartyService`, `MatchManager`, `QueueService`, `RoutingService`).
- `bigbanghub-common`: Implementações centrais em memória: `InMemoryPartyService`, `InMemoryMatchRegistry`, `AdmissionTicketService`, `RematchService`, `InMemoryQueueService`, `InMemoryInstanceRegistry`, codec binário `BBH1` (mensagens 1 a 31) com HMAC opcional.
- `bigbanghub-paper`: Plugin unificado para servidores Paper 26.2. Atua como núcleo do Lobby (`role: HUB`, com comando `/party`, executor de menus com checagem de líder e bússola) ou como controlador de partidas (`role: MINIGAME`, com `PaperMatchManager`, validação de tickets na entrada, reconexão e retorno seguro ao Hub com fallback kick).
- `bigbanghub-velocity`: Plugin para proxy Velocity 4.1.1. Orquestrador do cluster de instâncias, partidas globais, autoridade central de parties, despachador de matchmaking em grupo, emissor de tickets, HUD periódica e comandos `/party`, `/reconnect`, `/rematch`, `/playagain`.

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
bigbanghub-paper/build/libs/bigbanghub-paper-0.4.0.jar
bigbanghub-velocity/build/libs/bigbanghub-velocity-0.4.0.jar
bigbanghub-api/build/libs/bigbanghub-api-0.4.0.jar
bigbanghub-common/build/libs/bigbanghub-common-0.4.0.jar
```

---

## Comandos

### Comandos de Party (Proxy & Paper):
```text
/party [status]            # Exibe os membros, líder e estado atual da sua party
/party invite <jogador>    # Envia convite de party para um jogador
/party accept [jogador]    # Aceita convite pendente
/party decline [jogador]   # Recusa convite pendente
/party leave               # Sai da party atual
/party kick <jogador>      # Expulsa um membro da party (apenas líder)
/party leader <jogador>    # Transfere a liderança da party (apenas líder)
/party disband             # Dissolve a party (apenas líder)
/party warp                # Puxa todos os membros para o servidor atual do líder
```

### Comandos de Partida & Sessão:
```text
/reconnect                 # Tenta reconectar à última partida ativa
/playagain                 # Vota para jogar novamente após fim de partida
/rematch                   # Vota para revanche imediata com os mesmos jogadores
```

### Comandos de Fila & Administração (Velocity):
```text
/queue join <game>         # Entrar na fila de um minigame (líder entra com a party)
/queue leave               # Sair da fila
/queue status              # Consultar sua posição atual
/bbhub status              # Visão geral do cluster, instâncias, partidas, parties e filas
/bbhub instances           # Lista instâncias dinâmicas, saúde e partida ativa vinculada
/bbhub instance <id>       # Detalhes completos de uma instância
/bbhub matches             # Lista todas as partidas ativas na rede e contadores
/bbhub match <id>          # Detalhes da partida, participantes e papéis
/bbhub match <id> abort    # Força aborto de partida travada com retorno ao Hub
/bbhub return <player>     # Retorna forçadamente um jogador ao Hub com auditoria
/bbhub reload              # Recarrega configurações de forma segura
```

---

## Documentação Técnica

- [`docs/PARTIES.md`](docs/PARTIES.md): Especificação completa do subsistema de parties, invariantes, comandos, HUD e protocolo.
- [`docs/RECONNECT_REMATCH.md`](docs/RECONNECT_REMATCH.md): Guia de reconexão de sessão e votação de rematch / play again.
- [`docs/PERFORMANCE.md`](docs/PERFORMANCE.md): Relatório de benchmarks de carga (1.000 parties simultâneas), latência de matchmaking p99 e sweepers.
- [`docs/SECURITY.md`](docs/SECURITY.md): Segurança de ingressos, hardening de parties, sanitização de nomes, anti-spoofing e fallback kicks.
- [`docs/INTEGRATION_TESTING.md`](docs/INTEGRATION_TESTING.md): Arquitetura do test harness e validação do ciclo de vida ponta a ponta.
- [`docs/VALIDATION.md`](docs/VALIDATION.md): Relatório de validação staging/live sob diretrizes de segurança (LIVE_VALIDATION = NOT_RUN_SAFETY).
- [`docs/MATCH_LIFECYCLE.md`](docs/MATCH_LIFECYCLE.md): Máquina de estados de partidas, transições e tolerância a falhas.
- [`docs/MINIGAME_INTEGRATION.md`](docs/MINIGAME_INTEGRATION.md): Guia prático para integração de plugins de minigame.
- [`docs/INSTANCE_LIFECYCLE.md`](docs/INSTANCE_LIFECYCLE.md): Ciclo de vida das instâncias, liveness, heartbeats, reservas e handshake de limpeza.
- [`docs/CONFIGURATION.md`](docs/CONFIGURATION.md): Guia de configuração de todos os arquivos YAML (`party`, `experience`, `reconnect`, etc.).
- [`docs/OPERATIONS.md`](docs/OPERATIONS.md): Guia operacional, implantação, permissões e diagnóstico.
- [`docs/PROTOCOL.md`](docs/PROTOCOL.md): Catálogo de mensagens binárias `BBH1` (mensagens 1 a 31) e layouts.
