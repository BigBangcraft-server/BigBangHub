# Operações (BigBangHub 0.2.0)

## 1. Build e Artefatos

No diretório raiz:

```bash
./gradlew clean build --no-daemon
git diff --check
```

Os artefatos gerados são:

```text
bigbanghub-paper/build/libs/bigbanghub-paper-0.2.0.jar
bigbanghub-velocity/build/libs/bigbanghub-velocity-0.2.0.jar
bigbanghub-api/build/libs/bigbanghub-api-0.2.0.jar
bigbanghub-common/build/libs/bigbanghub-common-0.2.0.jar
```

---

## 2. Implantação nos Servidores

### No Proxy Velocity (`ubuntu2` - 10.8.0.1):
```text
/home/ubuntu/proxy/plugins/bigbanghub-velocity-0.2.0.jar
```

### No Servidor Hub (`brainiac` - 10.8.0.2):
```text
/home/brainiac/bigbangcraft/hubminigame/plugins/bigbanghub-paper-0.2.0.jar
```
No `config.yml`: `server.role: HUB`

### Nos Servidores de Minigame (`brainiac` - 10.8.0.2):
- BedWars: `/home/brainiac/bigbangcraft/bedward/plugins/bigbanghub-paper-0.2.0.jar`
- Campo Minado: `/home/brainiac/bigbangcraft/campominado/plugins/bigbanghub-paper-0.2.0.jar`
- HG: `/home/brainiac/bigbangcraft/hg/plugins/bigbanghub-paper-0.2.0.jar`

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
Exibe versão, protocolo, jogadores online no proxy, total de instâncias registradas, total de reservas ativas e resumo de cada jogo configurado.

### Listagem de Instâncias Runtime:
```text
/bbhub instances
```
Lista todas as instâncias dinâmicas conectadas:
```text
- campominado-01 [campominado] (campominado-01) HEALTHY WAITING 3/10 (res: 1) hb: 1.2s atrás s:4a8b1c9f
- bedwars-01 [bedwars] (bedwars-01) HEALTHY IN_GAME 8/16 (res: 0) hb: 0.8s atrás s:e2d4a1b0
```

### Detalhe de uma Instância:
```text
/bbhub instance <id>
```
Exibe estado completo, capacidade mínima/máxima, reservas ativas, instante exato do último heartbeat e UUID da sessão.

### Inspeção de Filas:
```text
/bbhub queues
```
Mostra o status de cada fila de jogo:
- Quantidade de jogadores na fila;
- Tempo de espera do jogador mais antigo (`Oldest wait`);
- Número de instâncias elegíveis para receber conexões;
- Estratégia de roteamento ativa.

### Detalhe de Fila por Jogo:
```text
/bbhub queue <game>
```
Lista os primeiros jogadores na fila em ordem estrita de prioridade FIFO.

### Métricas Internas:
```text
/bbhub metrics
```
Exibe contadores acumulados de telemetria:
- `Registrations`: Total de registros de instâncias processados.
- `Heartbeats`: Contagem de heartbeats recebidos vs rejeitados.
- `Routing`: Tentativas de roteamento vs falhas.
- `Transfers`: Transferências iniciadas, bem-sucedidas e falhas.
- `Reservation Expirations`: Quantidade de vagas liberadas por timeout.

---

## 4. Diagnóstico Rápido

| Sintoma | Causa Mais Provável | Ação Recomendada |
|---|---|---|
| Instância em `SUSPECT` | Perda temporária de pacote ou tick lag | Verificar se o minigame está travado ou executando garbage collection longo. |
| Instância em `UNAVAILABLE` | Minigame fechou ou caiu conexão de rádio | Verificar status do processo no host e logs do Paper. |
| Heartbeats sendo rejeitados (`rejected`) | Mensagens com `sessionId` desatualizado | Ocorre normalmente por alguns segundos após um restart; normaliza automaticamente. |
| Fila parada mesmo com jogadores | Nenhuma instância `HEALTHY` em `WAITING` | Verificar com `/bbhub instances` se os servidores estão em `IN_GAME` ou cheios. |
| Jogador redirecionado ao Hub | Servidor de minigame kickou ou caiu | Comportamento esperado de fallback; verificar motivo nos logs do backend. |
