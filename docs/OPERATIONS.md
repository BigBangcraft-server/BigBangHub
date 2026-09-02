# Operações

## Build e artefatos

Na checkout:

```bash
./gradlew clean build --no-daemon
git diff --check
```

Os artefatos são:

```text
bigbanghub-paper/build/libs/bigbanghub-paper-0.1.0.jar
bigbanghub-velocity/build/libs/bigbanghub-velocity-0.1.0.jar
```

## Instalação controlada

Paper:

```text
/home/brainiac/bigbangcraft/hubminigame/plugins/bigbanghub-paper-0.1.0.jar
```

Velocity:

```text
/home/ubuntu/proxy/plugins/bigbanghub-velocity-0.1.0.jar
```

O hub atual possui `HubMinigame.jar`, que também entrega bússola/proteção. Faça
backup e remova/desabilite o núcleo antigo na mesma janela de manutenção; não
rode os dois como donos dos mesmos eventos. Preserve FancyNpcs, FancyHolograms,
WorldGuard, LuckPerms, Vault, BungeeGuard, nLogin, VoidGen, FAWE, spark e
EasyCommandBlocker conforme a stack documentada.

Ordem segura: validar JARs em staging, copiar configuração, parar o proxy e o
hub de forma graciosa pelos scripts existentes, instalar os JARs, conferir que
`servers.yml` bate com `velocity.toml`, iniciar o proxy, iniciar o hub e então
verificar logs. Este projeto não executa deploy, restart ou alteração de mundo.

## Configuração inicial

O Velocity real já tem `hubminigame`, `bedwars`, `campominado` e `hg` registrados.
O plugin reutiliza os registros quando endereço coincide e só registra destinos
novos declarados. O backend Paper precisa manter `bigbanghub:main` e o mesmo
`hub-server-name`.

Para HMAC, injete `BIGBANGHUB_MESSAGE_SECRET` nos dois processos antes do start;
para o primeiro rollout pode deixá-lo ausente com `require-hmac: false`, usando
BungeeGuard, firewall e origem de conexão. Nunca coloque o segredo em YAML
versionado ou log.

## Comandos e logs

Paper: `/bbhub version`, `/bbhub status`, `/bbhub reload`, `/bbhub compass`.
Velocity: `/bbhub version`, `/bbhub status`, `/bbhub reload` e `/queue`.
Operadores precisam das permissões administrativas; comandos de jogador exigem
as permissões `bigbanghub.queue.*` correspondentes.

Logs úteis são `logs/latest.log` do hub e do proxy. Procure enable, quantidade de
jogos, rejeições de protocolo, servidor não permitido, reserva/transferência e
falhas de conexão. Não existe log de cada movimento/click nem de segredo.

## Rollback e diagnóstico

Rollback: pare graciosamente, restaure o JAR anterior e as configurações do
backup, inicie pelos scripts existentes e confirme que a fila anterior não foi
tratada como persistente. O estado de fila é deliberadamente em memória e se
perde em restart.

- `connection refused`: confira `10.8.0.2`, porta, tmux e firewall WireGuard.
- destino não permitido: confira ID em `servers.yml` e `velocity.toml`.
- timeout de plugin message: confira canal, Bungee plugin-message channel,
  BungeeGuard e se o jogador está no `hubminigame`.
- fila sem transferência: confira `state: WAITING`, `player-count/max-players`
  e se o minigame aceita conexões.
- reload rejeitado: corrija o caminho indicado; o snapshot anterior permanece ativo.
