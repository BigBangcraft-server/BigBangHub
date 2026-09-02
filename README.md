# BigBangHub 0.2.0

Infrastructure e Foundation de Hub/Lobby, Filas Globais e Registro de Instâncias Minigames para a rede **BigBangCraft** (Paper 26.2 e Velocity 4.1.1, Java 25).

A versão `0.2.0` evolui o sistema para uma infraestrutura completa de **Runtime Instance Registry**, **Liveness Tracking com Heartbeats**, **Slot Reservations transitórias** e **Roteamento Resiliente Orientado a Eventos**, suportando múltiplas instâncias dinâmicas de minigames (`bedwars`, `campominado`, `hg`) sem dependência de middlewares externos (sem Redis, sem SQL, sem brokers).

---

## Módulos

- `bigbanghub-api`: Contratos públicos, enums de lifecycle (`ServerRole`, `InstanceHealth`, `ReservationState`, `GameState`), records de snapshot/reserva e interfaces (`InstanceRegistry`, `InstanceService`, `QueueService`, `RoutingService`).
- `bigbanghub-common`: Implementações centrais puras em memória: `InMemoryInstanceRegistry`, `InMemoryReservationService`, `InMemoryQueueService`, `InstanceAwareRoutingService`, codec binário `BBH1` com HMAC opcional e parser de configurações transacionais.
- `bigbanghub-paper`: Plugin unificado para servidores Paper 26.2. Atua tanto como núcleo do Lobby principal (`role: HUB`, com bússola, menus e proteção) quanto como agente leve nos servidores de minigame (`role: MINIGAME`, com `PaperInstanceAgent`, publicação de estado e heartbeats).
- `bigbanghub-velocity`: Plugin para proxy Velocity 4.1.1. Orquestrador do cluster de instâncias, filas FIFO orientadas a eventos, alocação de vagas por reserva, confirmação de conexão e fallback automático para o Hub.

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
bigbanghub-paper/build/libs/bigbanghub-paper-0.2.0.jar
bigbanghub-velocity/build/libs/bigbanghub-velocity-0.2.0.jar
bigbanghub-api/build/libs/bigbanghub-api-0.2.0.jar
bigbanghub-common/build/libs/bigbanghub-common-0.2.0.jar
```

---

## Papéis de Servidor (`ServerRole`)

No Paper, defina a responsabilidade do servidor no `config.yml`:

- **`HUB`**: Ativa bússola, menu de minigames, atalhos de fila (`/queue`, `/campominado`) e proteções do lobby.
- **`MINIGAME`**: Desativa menus e proteções de lobby; ativa o `PaperInstanceAgent`, registrando a instância no Velocity e enviando heartbeats periódicos a cada 3 segundos com contagem de jogadores e estado de partida.
- **`GENERIC`**: Conexão básica de rede sem lobby protection.

---

## Comandos

### No Proxy Velocity:
```text
/bbhub status              # Visão geral do cluster e das filas
/bbhub instances           # Lista todas as instâncias dinâmicas e saúde
/bbhub instance <id>       # Detalhes completos de uma instância
/bbhub queues              # Status de todas as filas, espera e instâncias elegíveis
/bbhub queue <game>        # Detalhes dos jogadores na fila
/bbhub metrics             # Telemetria acumulada de requisições, transferências e heartbeats
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

- [`docs/INSTANCE_LIFECYCLE.md`](docs/INSTANCE_LIFECYCLE.md): Ciclo de vida completo das instâncias, liveness, sessões, reservas e guia para desenvolvedores de minigames.
- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md): Arquitetura do cluster, topologia real e garantias de concorrência.
- [`docs/API.md`](docs/API.md): Contratos públicos e integração Java.
- [`docs/PROTOCOL.md`](docs/PROTOCOL.md): Especificação dos envelopes binários `BBH1` e catálogo de mensagens.
- [`docs/CONFIGURATION.md`](docs/CONFIGURATION.md): Guia de configuração de todos os arquivos YAML.
- [`docs/OPERATIONS.md`](docs/OPERATIONS.md): Guia operacional, implantação e diagnóstico.
- [`docs/SECURITY.md`](docs/SECURITY.md): Isolamento de backends, allowlists, rate limiting e autenticação HMAC.
