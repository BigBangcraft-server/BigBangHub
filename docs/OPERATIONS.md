# Operações (BigBangHub 0.3.0)

## 1. Build e Artefatos

No diretório raiz:

```bash
./gradlew clean build --no-daemon
git diff --check
```

Os artefatos gerados são:

```text
bigbanghub-paper/build/libs/bigbanghub-paper-0.3.0.jar
bigbanghub-velocity/build/libs/bigbanghub-velocity-0.3.0.jar
bigbanghub-api/build/libs/bigbanghub-api-0.3.0.jar
bigbanghub-common/build/libs/bigbanghub-common-0.3.0.jar
```

---

## 2. Implantação nos Servidores

### No Proxy Velocity (`ubuntu2` - 10.8.0.1):
```text
/home/ubuntu/proxy/plugins/bigbanghub-velocity-0.3.0.jar
```

### No Servidor Hub (`brainiac` - 10.8.0.2):
```text
/home/brainiac/bigbangcraft/hubminigame/plugins/bigbanghub-paper-0.3.0.jar
```
No `config.yml`: `server.role: HUB`

### Nos Servidores de Minigame (`brainiac` - 10.8.0.2):
- BedWars: `/home/brainiac/bigbangcraft/bedward/plugins/bigbanghub-paper-0.3.0.jar`
- Campo Minado: `/home/brainiac/bigbangcraft/campominado/plugins/bigbanghub-paper-0.3.0.jar`
- HG: `/home/brainiac/bigbangcraft/hg/plugins/bigbanghub-paper-0.3.0.jar`

No `config.yml` de cada minigame:
```yaml
server:
  role: MINIGAME
  instance:
    instance-id: campominado-01 # (ou bedwars-01, hg-01)
    game-id: campominado        # (ou bedwars, hg)
    server-name: campominado-01
```

*Atenção: NÃO reinicie nem modifique servidores de produção sem janela controlada de manutenção.*

---

## 3. Comandos Administrativos e Inspeção em Produção

O Velocity conta com ferramentas completas de telemetria operacional via comando `/bbhub`:

### Visão Geral do Cluster:
```text
/bbhub status
```
Exibe versão, protocolo, jogadores online no proxy, instâncias registradas, partidas ativas, reservas ativas e status das filas.

### Listagem de Instâncias Runtime:
```text
/bbhub instances
```
Lista todas as instâncias dinâmicas conectadas:
```text
- campominado-01 [campominado] (campominado-01) HEALTHY WAITING 3/10 (res: 1) match:0191a2b3 hb: 1.2s atrás s:4a8b1c9f
```

### Detalhe de uma Instância:
```text
/bbhub instance <id>
```
Exibe estado completo, capacidade mínima/máxima, reservas ativas, partida ativa associada, instante do último heartbeat e UUID da sessão.

### Listagem de Partidas Ativas:
```text
/bbhub matches
```
Lista todas as partidas ativas na rede:
```text
Match: 0191a2b3-c4d5-7e8f-9a0b-1c2d3e4f5a6b | Game: campominado | Instance: campominado-01 | State: WAITING | Players: 3/10 (res: 1) | Spectators: 0 | Rev: 2 | Age: 25s
```

### Detalhe de uma Partida:
```text
/bbhub match <id>
```
Exibe todos os detalhes da sessão: jogo, servidor, arena, estado atual, revisão monotônica, contadores de jogadores/espectadores, timestamps e lista de participantes com papéis e estados.

### Aborto Forçado de Partida:
```text
/bbhub match <id> abort
```
Permissão: `bigbanghub.admin.match.abort` ou `bigbanghub.admin`.
Força o encerramento imediato de uma partida travada, marca estado `ABORTED`, conduz retorno seguro de todos os jogadores para o Hub e registra log de auditoria no console.

### Retorno Forçado de Jogador ao Hub:
```text
/bbhub return <player>
```
Permissão: `bigbanghub.admin.player.return` ou `bigbanghub.admin`.
Desconecta o jogador da partida atual, cancela reservas/tickets associados e o transfere em segurança para o Hub principal (`hubminigame`), com log de auditoria.

### Inspeção de Filas:
```text
/bbhub queues
/bbhub queue <game>
```
Mostra o status de cada fila de jogo e a ordem de prioridade FIFO.

### Métricas e Telemetria:
```text
/bbhub metrics
```
Exibe contadores acumulados de telemetria:
- `Registrations`: Total de registros de instâncias processados.
- `Heartbeats`: Recebidos vs rejeitados.
- `Matches`: Criadas, iniciadas, finalizadas e abortadas.
- `Admissions`: Ingressos aceitos vs rejeitados.
- `Routing`: Tentativas vs falhas.
- `Transfers`: Iniciadas, concluídas, falhas e falhas de retorno ao Hub.
- `Reservation Expirations`: Quantidade de vagas liberadas por timeout.

---

## 4. Permissões Administrativas

| Permissão | Finalidade | Padrão |
|---|---|:---:|
| `bigbanghub.admin` | Permissão mestra para todos os comandos administrativos | op |
| `bigbanghub.admin.matches` | Visualizar listagem de partidas ativas (`/bbhub matches`) | op |
| `bigbanghub.admin.match.inspect` | Visualizar detalhes de uma partida (`/bbhub match <id>`) | op |
| `bigbanghub.admin.match.abort` | Abortar forçadamente uma partida (`/bbhub match <id> abort`) | op |
| `bigbanghub.admin.player.return` | Retornar forçadamente jogador ao Hub (`/bbhub return <player>`) | op |
| `bigbanghub.reload` | Recarregar arquivos de configuração (`/bbhub reload`) | op |

---

## 5. Diagnóstico Rápido

| Sintoma | Causa Mais Provável | Ação Recomendada |
|---|---|---|
| Instância em `SUSPECT` | Perda temporária de pacote ou tick lag | Verificar se o minigame está travado ou executando GC longo. |
| Instância em `UNAVAILABLE` | Processo caiu ou desconectou | Verificar logs do Paper e reiniciar serviço na janela permitida. |
| Admissões sendo rejeitadas | Tentativa de conexão direta sem ticket ou ticket expirado | Normal para conexões manuais via `/server`. Jogador é retornado ao Hub com segurança. |
| Partida travada em `IN_GAME` | Minigame não disparou evento de finish/abort | Inspecionar com `/bbhub match <id>` e executar `/bbhub match <id> abort` se necessário. |
| Instância não aceita nova partida | Limpeza pendente (`markReady` não chamado) | O minigame ainda está executando reset de arena ou o cleanup falhou. |
| Fila parada com jogadores | Partidas em andamento cheias ou sem capacidade | Conferir `/bbhub matches` para checar capacidade e estado das sessões. |
