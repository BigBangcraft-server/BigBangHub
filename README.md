# BigBangHub

Foundation leve do Hub/Lobby da BigBangCraft para Paper 26.2 e Velocity 4.1.1.
O plugin fornece seleção de minigames, ações configuráveis, filas em memória,
roteamento, transferência segura e proteção do lobby. Ele não implementa regras
de nenhum minigame.

## Módulos

- `bigbanghub-api`: contratos públicos para integrações.
- `bigbanghub-common`: modelos imutáveis, validação, protocolo e fila/roteamento.
- `bigbanghub-paper`: bússola, menu, aliases, ações, proteção e ponte com o proxy.
- `bigbanghub-velocity`: registro de servidores, filas autoritativas e transferências.

## Requisitos e build

- Java 25 (igual ao Paper 26.2 da rede).
- Gradle Wrapper 8.14.3.
- API compilada contra `io.papermc.paper:paper-api:26.2.build.121-stable`, último build 26.2 disponível no repositório oficial no momento do build.

```bash
./gradlew clean build --no-daemon
```

Artefatos:

```text
bigbanghub-paper/build/libs/bigbanghub-paper-0.1.0.jar
bigbanghub-velocity/build/libs/bigbanghub-velocity-0.1.0.jar
bigbanghub-api/build/libs/bigbanghub-api-0.1.0.jar
```

## Instalação rápida

1. Instale o JAR Paper em `/home/brainiac/bigbangcraft/hubminigame/plugins/`.
2. Instale o JAR Velocity em `/home/ubuntu/proxy/plugins/`.
3. Mantenha `games.yml`, `servers.yml`, `config.yml` e o segredo opcional alinhados nos dois plugins.
4. Faça um restart controlado; use `/bbhub reload` apenas para mudanças reloadable.

O servidor documentado usa Paper `26.2-120`, Java 25, hub `hubminigame` e os
destinos `bedwars`, `campominado` e `hg`. O build 121 é um patch da mesma linha;
valide-o primeiro em staging antes de atualizar o Paper de produção.

O BigBangHub substitui o núcleo customizado de lobby que hoje é o
`HubMinigame.jar`; não instale ambos como donos de bússola/proteção. FancyNpcs,
FancyHolograms, WorldGuard, LuckPerms, BungeeGuard e EasyCommandBlocker podem
continuar. Para filas, configure os NPCs para executar o alias do jogo; a
ação atual FancyNpcs `send_to_server` é transferência direta e não entra na fila.

## Uso

```text
/bbhub compass
/bbhub status
/bbhub reload
/queue join campominado
/queue leave
/queue status
/campominado
```

O alias `/campominado` vem de `config.yml` e pode ser trocado sem recompilar.
Veja o exemplo completo em [`docs/CONFIGURATION.md`](docs/CONFIGURATION.md).

## Documentação

- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)
- [`docs/API.md`](docs/API.md)
- [`docs/PROTOCOL.md`](docs/PROTOCOL.md)
- [`docs/CONFIGURATION.md`](docs/CONFIGURATION.md)
- [`docs/OPERATIONS.md`](docs/OPERATIONS.md)
- [`docs/SECURITY.md`](docs/SECURITY.md)
