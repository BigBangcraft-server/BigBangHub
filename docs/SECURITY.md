# Segurança e Hardening (BigBangHub 0.2.0)

## 1. Fronteiras de Confiança e Isolamento

- **Clientes (Jogadores)**: Totalmente não confiáveis. Não escolhem servidores de destino, portas, nem manipulam payloads internos.
- **Servidores Paper (Backends)**: Confiáveis dentro de seu próprio escopo. Um backend só pode registrar e atualizar instâncias correspondentes ao seu nome de conexão ou às regras de allowlist declaradas no proxy.
- **Proxy Velocity**: Autoridade central absoluta sobre filas, registros em runtime, contagem de jogadores, alocação de vagas e transferências.
- **Isolamento de Rede**: O tráfego de backend transita via túnel WireGuard (`10.8.0.x`). BungeeGuard assegura que conexões diretas não autorizadas à porta dos backends sejam rejeitadas.

---

## 2. Validação de Identidade e Allowlists

Na versão 0.2.0, para impedir que um backend comprometido tente sequestrar outros servidores da rede (ex: um minigame afirmando ser `survival` ou `hubminigame`):

1. **Validação de Origem**: O Velocity confronta o nome da conexão física (`connection.getServerInfo().getName()`) com o `instanceId` declarado no payload `INSTANCE_REGISTER`.
2. **Allowlist Configurável**: Padrões explícitos podem ser configurados em `registry.allowed` no Velocity (ex: `campominado-*` mapeado exclusivamente para o jogo `campominado`).
3. **Existência no Proxy**: O servidor deve existir previamente na configuração do Velocity (`proxy.getServer(serverName).isPresent()`). Se o servidor não estiver cadastrado no proxy, o registro é sumariamente recusado com `INSTANCE_REGISTER_ACK(success=false)`.

---

## 3. Prevenção de Ataques de Replay e Dessincronização

1. **UUID Session ID por Processo**:
   - Toda execução de servidor gera um `sessionId` criptograficamente seguro.
   - O proxy vincula a instância ao `sessionId`.
   - Mensagens com `sessionId` divergente (ex: pacotes atrasados em buffer TCP após reinicialização) são ignoradas instantaneamente (`REJECTED_STALE_SESSION`), evitando corrupção de estado por condições de corrida.
2. **TTL de Reservas de Vaga (Anti-Starvation)**:
   - Um jogador ou backend malicioso não pode segurar uma vaga indefinidamente. Toda reserva expira em `reservation-ttl` (padrão: 10s).
   - A varredura contínua de 1 segundo cancela reservas expiradas, liberando o slot para outros jogadores.
3. **Limite de 1 Reserva Ativa por Jogador**:
   - Um jogador só pode possuir no máximo 1 reserva ativa em todo o proxy ao mesmo tempo, impedindo ataques de exaustão de vagas por concorrência artificial.

---

## 4. Rate Limiting

- **Por Jogador**: Intervalo mínimo de 100 ms entre requisições de fila ou conexão (`rateAllowed`).
- **Por Backend**: Limite de segurança de 50 mensagens por segundo por servidor backend (`backendRateAllowed`), prevenindo sobrecarga do event loop do proxy por flooding de pacotes.

---

## 5. Criptografia e Autenticação de Mensagens

- Quando `BIGBANGHUB_MESSAGE_SECRET` está definido no ambiente, todo o envelope binário `BBH1` é assinado com **HMAC-SHA256**.
- A verificação de assinatura utiliza comparação em tempo constante (`MessageDigest.isEqual`) para prevenir ataques de temporização (*timing attacks*).
- Com `require-hmac: true`, pacotes não autenticados ou adulterados são rejeitados na camada de decodificação.
- O segredo nunca é gravado em arquivos de configuração versionados no Git nem exposto em logs.
