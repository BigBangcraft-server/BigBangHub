# API de integração

`bigbanghub-api` é o contrato público versionado como parte de `0.1.0`.
Integrações no mesmo build usam:

```groovy
implementation project(':bigbanghub-api')
```

Para um consumidor externo, publique o módulo com
`./gradlew :bigbanghub-api:publishToMavenLocal` e use:

```groovy
repositories { mavenLocal() }
dependencies { implementation 'com.bigbangcraft:bigbanghub-api:0.1.0' }
```

## Acesso no Paper

O plugin registra `BigBangHubApi` no `ServicesManager`:

```java
RegisteredServiceProvider<BigBangHubApi> registration =
    Bukkit.getServicesManager().getRegistration(BigBangHubApi.class);
if (registration != null) {
    BigBangHubApi hub = registration.getProvider();
    hub.queues().join(player.getUniqueId(), GameId.of("campominado"))
        .thenAccept(result -> player.sendMessage(result.message()));
}
```

Não guarde a implementação concreta nem um singleton. Consulte o serviço no
enable do plugin consumidor e remova listeners no disable.

## Contratos

- `GameRegistry`: jogos imutáveis carregados da configuração.
- `ServerRegistry`: destinos lógicos e estado/capacidade observados.
- `QueueService`: `join`, `leave`, `status`, `contains` e `size`; chamadas são
  seguras para concorrência e retornam `CompletionStage` nas operações remotas.
- `RoutingService`: seleção determinística sem conhecer menus.
- `PlayerTransferService`: transferência por `ServerId`, nunca por IP recebido
  do jogador.
- `GameId` e `ServerId`: validam IDs minúsculos com caracteres limitados.

## Eventos

`QueueJoinedEvent`, `QueueLeftEvent` e `QueueAssignedEvent` são observacionais,
registrados com `addQueueListener`. Não são canceláveis: o evento é publicado
depois da alteração atômica. O callback é síncrono no thread da operação; um
consumidor que faz trabalho pesado deve despachar seu próprio trabalho.

## Lifecycle, erros e compatibilidade

O API Paper existe depois de `onEnable` e antes de `onDisable`. `CompletionStage`
de fila pode completar com erro quando Velocity não está conectado; o chamador
deve exibir fallback. `QueueResult` e `TransferResult` carregam código/mensagem
sem lançar exceção por indisponibilidade normal.

`0.1.x` mantém os tipos e o protocolo v1 compatíveis. Mudanças incompatíveis
exigem nova versão major do API e nova versão de protocolo; o projeto não tenta
desserializar uma versão futura como v1.
