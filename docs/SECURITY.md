# Segurança e Hardening (BigBangHub 0.3.0)

## 1. Fronteiras de Confiança e Isolamento

- **Clientes (Jogadores)**: Totalmente não confiáveis. Não escolhem servidores de destino, portas, nem manipulam tickets ou estados de partida.
- **Servidores Paper (Backends)**: Confiáveis dentro de seu próprio escopo. Um backend só pode registrar instâncias e atualizar partidas associadas à sua identidade validada.
- **Proxy Velocity**: Autoridade central absoluta sobre filas, registro de partidas, validação de tickets de admissão e transferências.
- **Isolamento de Rede**: O tráfego de backend transita via túnel WireGuard (`10.8.0.x`). BungeeGuard assegura que conexões diretas não autorizadas à porta dos backends sejam rejeitadas.

---

## 2. Segurança de Ingressos de Admissão (`AdmissionTicket`)

O BigBangHub 0.3.0 introduz tickets criptográficos transitórios para combater acessos indevidos e conexões manuais diretas:

1. **Vínculo Estrito**: Todo ticket é emitido pelo Velocity e vinculado de forma imutável a:
   - `playerId`: UUID do jogador;
   - `matchId`: ID da partida autorizada;
   - `instanceId`: Servidor físico onde a partida ocorre;
   - `token`: Nonce criptográfico aleatório gerado no momento do roteamento.
2. **Uso Único (Single-Use)**: O ticket é imediatamente removido e invalidado no primeiro consumo (`ticketService.consume(...)`). Qualquer tentativa posterior de reuso resulta em `REPLAY_ATTACK_REJECTED`.
3. **TTL Bounded**: Prazo de validade estrito (`admission-timeout`, padrão 10s). Tickets órfãos por falha de conexão expiram automaticamente.
4. **Política de Entrada Direta (`DIRECT_JOIN_REJECTED`)**:
   - Conexões sem ticket ou com ticket inválido/expirado não resultam em kicks ou bans punitivos.
   - O jogador é interceptado na chegada e conduzido de forma transparente e segura de volta ao Hub principal (`hubminigame`), preservando a estabilidade e a experiência do usuário.

---

## 3. Invariante de Sessão e Prevenção de Concorrência

1. **Invariante de Partida Única por Jogador**:
   - Um jogador só pode estar em **uma partida ativa** na rede em qualquer momento.
   - O `InMemoryMatchRegistry` mapeia `activeByPlayer` com CAS atômico. Se o jogador tentar entrar em uma segunda partida sem ter saído da anterior, a admissão é rejeitada com `ErrorCode.PLAYER_ALREADY_ASSIGNED`.
2. **Revisões Monotônicas (`CAS Protection`)**:
   - Toda transição de estado da partida incrementa `revision`.
   - Mensagens de transição carregam a revisão esperada. Mensagens desordenadas ou com revisão defasada são descartadas para impedir regressão de estados.
3. **Handshake de Limpeza (`markInstanceReady`)**:
   - Instâncias finalizadas permanecem presas à partida anterior até a conclusão do reset de arena, impedindo que novos jogadores spawnem em mapas não resetados.

---

## 4. Auditoria de Ações Administrativas

Operações que interferem no estado de partidas em produção geram logs de auditoria explícitos com nível `INFO`:
- Aborto forçado: `AUDIT: Admin <origem> aborted match <matchId>`
- Retorno forçado: `AUDIT: Admin <origem> returned player <jogador> to hub`

---

## 5. Rate Limiting e HMAC

- **Rate Limiting por Jogador**: Máximo de 1 requisição a cada 100 ms.
- **Rate Limiting por Backend**: Máximo de 50 mensagens por segundo por servidor backend.
- **HMAC-SHA256**: Envelopes binários `BBH1` assinados quando `BIGBANGHUB_MESSAGE_SECRET` estiver configurado, com verificação em tempo constante (`MessageDigest.isEqual`) contra timing attacks.
