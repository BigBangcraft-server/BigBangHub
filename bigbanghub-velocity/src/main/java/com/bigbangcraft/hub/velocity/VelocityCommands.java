package com.bigbangcraft.hub.velocity;

import com.bigbangcraft.hub.api.GameDefinition;
import com.bigbangcraft.hub.api.GameId;
import com.bigbangcraft.hub.api.InstanceSnapshot;
import com.bigbangcraft.hub.api.MatchId;
import com.bigbangcraft.hub.api.MatchParticipant;
import com.bigbangcraft.hub.api.MatchSnapshot;
import com.bigbangcraft.hub.api.ReturnReason;
import com.bigbangcraft.hub.api.ServerId;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

final class VelocityCommands implements SimpleCommand {
    private final BigBangHubVelocityPlugin plugin;
    private final boolean queueCommand;

    VelocityCommands(BigBangHubVelocityPlugin plugin, boolean queueCommand) {
        this.plugin = plugin;
        this.queueCommand = queueCommand;
    }

    @Override
    public void execute(Invocation invocation) {
        if (queueCommand) executeQueue(invocation);
        else executeHub(invocation);
    }

    private void executeHub(Invocation invocation) {
        String[] args = invocation.arguments();
        String sub = args.length == 0 ? "version" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "version" -> invocation.source().sendPlainMessage("BigBangHub 0.3.0 (Velocity 4.1.1)");
            case "reload" -> {
                if (!admin(invocation.source(), "bigbanghub.reload")) return;
                plugin.reload(invocation.source());
            }
            case "status" -> {
                if (!admin(invocation.source(), "bigbanghub.admin")) return;
                status(invocation.source());
            }
            case "instances" -> {
                if (!admin(invocation.source(), "bigbanghub.admin")) return;
                instances(invocation.source());
            }
            case "instance" -> {
                if (!admin(invocation.source(), "bigbanghub.admin")) return;
                if (args.length < 2) {
                    invocation.source().sendPlainMessage("Use: /bbhub instance <id>");
                    return;
                }
                instanceDetail(invocation.source(), args[1]);
            }
            case "matches" -> {
                if (!admin(invocation.source(), "bigbanghub.admin.matches")) return;
                matches(invocation.source());
            }
            case "match" -> matchCommand(invocation.source(), args);
            case "return" -> {
                if (args.length < 2) {
                    invocation.source().sendPlainMessage("Use: /bbhub return <player>");
                    return;
                }
                returnCommand(invocation.source(), args[1]);
            }
            case "queues" -> {
                if (!admin(invocation.source(), "bigbanghub.admin")) return;
                queues(invocation.source());
            }
            case "queue" -> {
                if (!admin(invocation.source(), "bigbanghub.admin")) return;
                if (args.length < 2) {
                    invocation.source().sendPlainMessage("Use: /bbhub queue <game>");
                    return;
                }
                queueDetail(invocation.source(), args[1]);
            }
            case "metrics" -> {
                if (!admin(invocation.source(), "bigbanghub.admin")) return;
                metrics(invocation.source());
            }
            default -> invocation.source().sendPlainMessage(
                    "Use: /bbhub <status|instances|instance|matches|match|return|queues|queue|metrics|reload|version>");
        }
    }

    private void executeQueue(Invocation invocation) {
        if (!(invocation.source() instanceof Player player)) {
            invocation.source().sendPlainMessage("Este comando só pode ser usado por jogadores.");
            return;
        }
        String[] args = invocation.arguments();
        if (args.length == 0) {
            player.sendPlainMessage("Use: /queue <join|leave|status> [game]");
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "join" -> {
                if (!player.hasPermission("bigbanghub.queue.join")) { deny(player); return; }
                if (args.length != 2) { player.sendPlainMessage("Use: /queue join <game>"); return; }
                try {
                    GameId game = GameId.of(args[1]);
                    if (!plugin.games().find(game).map(g -> g.enabled() && g.queueEnabled()).orElse(false)) {
                        player.sendPlainMessage("Este minigame está temporariamente indisponível.");
                        return;
                    }
                    plugin.join(player, game);
                } catch (IllegalArgumentException exception) {
                    player.sendPlainMessage("Game ID inválido.");
                }
            }
            case "leave" -> {
                if (!player.hasPermission("bigbanghub.queue.leave")) { deny(player); return; }
                plugin.leave(player);
            }
            case "status" -> {
                if (!player.hasPermission("bigbanghub.queue.status")) { deny(player); return; }
                plugin.queueStatus(player.getUniqueId()).thenAccept(status -> {
                    if (status.game().isEmpty()) player.sendPlainMessage("Você não está em uma fila.");
                    else player.sendPlainMessage("Fila: " + status.game().orElseThrow() + " | posição: "
                            + status.position() + "/" + status.size());
                });
            }
            default -> player.sendPlainMessage("Use: /queue <join|leave|status> [game]");
        }
    }

    private void status(CommandSource source) {
        source.sendPlainMessage("=== BigBangHub 0.3.0 Status ===");
        source.sendPlainMessage("Protocol: " + plugin.configSnapshot().proxy().protocolVersion()
                + " | Players online: " + plugin.proxy().getPlayerCount());
        source.sendPlainMessage("Configured Games: " + plugin.games().games().size()
                + " | Registered Instances: " + plugin.instances().instances().size()
                + " | Active Matches: " + plugin.matchRegistry().activeMatches().size()
                + " | Active Reservations: " + plugin.reservationService().activeCount());
        for (GameDefinition game : plugin.games().games()) {
            int queueSize = plugin.queues().size(game.id());
            long eligible = plugin.instances().instancesForGame(game.id()).stream()
                    .filter(InstanceSnapshot::canAcceptPlayers)
                    .count();
            long activeMatches = plugin.matchRegistry().activeMatchesForGame(game.id()).size();
            source.sendPlainMessage("• " + game.id() + ": " + queueSize + " queued | " + activeMatches
                    + " active matches | " + eligible + " eligible instances | strategy=" + game.routingStrategy());
        }
    }

    private void instances(CommandSource source) {
        source.sendPlainMessage("=== Runtime Instances (" + plugin.instances().instances().size() + ") ===");
        Instant now = Instant.now();
        Collection<InstanceSnapshot> list = plugin.instances().instances();
        if (list.isEmpty()) {
            source.sendPlainMessage("Nenhuma instância registrada no momento.");
            return;
        }
        for (InstanceSnapshot inst : list) {
            String ago = Duration.between(inst.lastHeartbeat(), now).toMillis() / 1000.0 + "s atrás";
            String sessionShort = inst.sessionId().toString().substring(0, 8);
            String activeMatch = plugin.matchRegistry().findActiveForInstance(inst.instanceId())
                    .map(m -> m.matchId().value().substring(0, Math.min(8, m.matchId().value().length())))
                    .orElse("none");
            source.sendPlainMessage(String.format("- %s [%s] (%s) %s %s %d/%d (res: %d) match:%s hb: %s s:%s",
                    inst.instanceId().value(), inst.gameId().value(), inst.serverName(),
                    inst.health(), inst.state(), inst.playerCount(), inst.maxPlayers(),
                    inst.activeReservations(), activeMatch, ago, sessionShort));
        }
    }

    private void instanceDetail(CommandSource source, String idStr) {
        try {
            ServerId id = ServerId.of(idStr);
            InstanceSnapshot inst = plugin.instances().find(id).orElse(null);
            if (inst == null) {
                source.sendPlainMessage("Instância '" + idStr + "' não encontrada no registro runtime.");
                return;
            }
            Instant now = Instant.now();
            String ago = Duration.between(inst.lastHeartbeat(), now).toMillis() / 1000.0 + "s atrás";
            source.sendPlainMessage("=== Detalhes da Instância: " + inst.instanceId().value() + " ===");
            source.sendPlainMessage("Game: " + inst.gameId().value() + " | Backend: " + inst.serverName());
            source.sendPlainMessage("Health: " + inst.health() + " | State: " + inst.state() + " | Accepting: " + inst.acceptingPlayers());
            source.sendPlainMessage("Players: " + inst.playerCount() + " (min: " + inst.minPlayers() + ", max: " + inst.maxPlayers() + ")");
            source.sendPlainMessage("Active Reservations: " + inst.activeReservations() + " | Effective Capacity: " + inst.effectiveCapacity());
            source.sendPlainMessage("Last Heartbeat: " + inst.lastHeartbeat() + " (" + ago + ")");
            source.sendPlainMessage("Session ID: " + inst.sessionId());

            plugin.matchRegistry().findActiveForInstance(id).ifPresent(m -> {
                source.sendPlainMessage("Active Match: " + m.matchId() + " (" + m.state() + ", players: "
                        + m.participantCount() + "/" + m.maxPlayers() + ")");
            });
        } catch (IllegalArgumentException e) {
            source.sendPlainMessage("ID de servidor inválido: " + idStr);
        }
    }

    private void matches(CommandSource source) {
        Collection<MatchSnapshot> active = plugin.matchRegistry().activeMatches();
        source.sendPlainMessage("=== Active Matches (" + active.size() + ") ===");
        if (active.isEmpty()) {
            source.sendPlainMessage("Nenhuma partida ativa no momento.");
            return;
        }
        Instant now = Instant.now();
        for (MatchSnapshot m : active) {
            long ageSec = Duration.between(m.createdAt(), now).toSeconds();
            source.sendPlainMessage(String.format("Match: %s | Game: %s | Instance: %s | State: %s | Players: %d/%d (res: %d) | Spectators: %d | Rev: %d | Age: %ds",
                    m.matchId().value(), m.gameId().value(), m.instanceId().value(),
                    m.state(), m.participantCount(), m.maxPlayers(), m.pendingAdmissions(),
                    m.spectatorCount(), m.revision(), ageSec));
        }
    }

    private void matchCommand(CommandSource source, String[] args) {
        if (args.length < 2) {
            source.sendPlainMessage("Use: /bbhub match <id> [abort]");
            return;
        }
        MatchId matchId;
        try {
            matchId = MatchId.of(args[1]);
        } catch (IllegalArgumentException e) {
            source.sendPlainMessage("Match ID inválido: " + args[1]);
            return;
        }

        if (args.length >= 3 && args[2].equalsIgnoreCase("abort")) {
            if (!admin(source, "bigbanghub.admin.match.abort")) return;
            String adminName = (source instanceof Player p) ? p.getUsername() : "Console";
            boolean aborted = plugin.matchRegistry().abortMatch(matchId, "Force aborted by admin " + adminName, Instant.now());
            if (aborted) {
                plugin.matchRegistry().findSession(matchId).ifPresent(s -> {
                    plugin.safeReturnPlayersToHub(
                            s.participants().stream().map(MatchParticipant::playerId).toList(),
                            ReturnReason.ADMIN_FORCE_RETURN, "Match aborted by admin");
                });
                plugin.getLogger().info("AUDIT: Admin {} aborted match {}", adminName, matchId);
                source.sendPlainMessage("Partida " + matchId + " abortada com sucesso.");
            } else {
                source.sendPlainMessage("Não foi possível abortar a partida " + matchId + " (já finalizada ou não encontrada).");
            }
            return;
        }

        if (!admin(source, "bigbanghub.admin.match.inspect")) return;
        MatchSnapshot match = plugin.matchRegistry().find(matchId).orElse(null);
        if (match == null) {
            source.sendPlainMessage("Partida " + matchId + " não encontrada.");
            return;
        }

        Instant now = Instant.now();
        long ageSec = Duration.between(match.createdAt(), now).toSeconds();
        source.sendPlainMessage("=== Detalhes da Partida: " + match.matchId().value() + " ===");
        source.sendPlainMessage("Game: " + match.gameId().value() + " | Instance: " + match.instanceId().value()
                + " | Arena: " + match.arenaId().orElse("default"));
        source.sendPlainMessage("State: " + match.state() + " | Revision: " + match.revision() + " | Age: " + ageSec + "s");
        source.sendPlainMessage("Players: " + match.participantCount() + "/" + match.maxPlayers()
                + " (min: " + match.minPlayers() + ") | Pending Admissions: " + match.pendingAdmissions());
        source.sendPlainMessage("Spectators: " + match.spectatorCount());
        source.sendPlainMessage("Created: " + match.createdAt());
        match.startedAt().ifPresent(st -> source.sendPlainMessage("Started: " + st));
        match.endedAt().ifPresent(et -> source.sendPlainMessage("Ended: " + et));

        plugin.matchRegistry().findSession(matchId).ifPresent(session -> {
            Collection<MatchParticipant> participants = session.participants();
            source.sendPlainMessage("Participants (" + participants.size() + "):");
            for (MatchParticipant p : participants) {
                String name = plugin.proxy().getPlayer(p.playerId()).map(Player::getUsername).orElse(p.playerId().toString());
                source.sendPlainMessage("  - " + name + " [" + p.role() + "] (" + p.state() + ")");
            }
        });
    }

    private void returnCommand(CommandSource source, String playerName) {
        if (!admin(source, "bigbanghub.admin.player.return")) return;
        Player target = plugin.proxy().getPlayer(playerName).orElse(null);
        if (target == null) {
            source.sendPlainMessage("Jogador '" + playerName + "' não encontrado online.");
            return;
        }
        String adminName = (source instanceof Player p) ? p.getUsername() : "Console";
        plugin.safeReturnPlayerToHub(target.getUniqueId(), ReturnReason.ADMIN_FORCE_RETURN, "Admin force return");
        plugin.getLogger().info("AUDIT: Admin {} returned player {} to hub", adminName, target.getUsername());
        source.sendPlainMessage("Jogador " + target.getUsername() + " retornado ao Hub.");
    }

    private void queues(CommandSource source) {
        source.sendPlainMessage("=== Filas de Minigames ===");
        long nowNanos = System.nanoTime();
        for (GameDefinition game : plugin.games().games()) {
            int queued = plugin.queues().size(game.id());
            long oldestWaitSec = plugin.queueService().oldestWaitNanos(game.id(), nowNanos) / 1_000_000_000L;
            long eligible = plugin.instances().instancesForGame(game.id()).stream()
                    .filter(InstanceSnapshot::canAcceptPlayers)
                    .count();
            source.sendPlainMessage("Game: " + game.id().value());
            source.sendPlainMessage("  Queued: " + queued);
            source.sendPlainMessage("  Oldest wait: " + oldestWaitSec + "s");
            source.sendPlainMessage("  Eligible instances: " + eligible);
            source.sendPlainMessage("  Routing strategy: " + game.routingStrategy());
        }
    }

    private void queueDetail(CommandSource source, String gameStr) {
        try {
            GameId gameId = GameId.of(gameStr);
            int queued = plugin.queues().size(gameId);
            long nowNanos = System.nanoTime();
            long oldestWaitSec = plugin.queueService().oldestWaitNanos(gameId, nowNanos) / 1_000_000_000L;
            List<java.util.UUID> players = plugin.queueService().queuedPlayers(gameId);

            source.sendPlainMessage("=== Fila: " + gameId.value() + " ===");
            source.sendPlainMessage("Queued: " + queued + " | Oldest wait: " + oldestWaitSec + "s");
            source.sendPlainMessage("Players na fila: " + players.size());
            for (int i = 0; i < Math.min(players.size(), 10); i++) {
                java.util.UUID pid = players.get(i);
                String name = plugin.proxy().getPlayer(pid).map(Player::getUsername).orElse(pid.toString());
                source.sendPlainMessage("  " + (i + 1) + ". " + name);
            }
            if (players.size() > 10) {
                source.sendPlainMessage("  ... e mais " + (players.size() - 10) + " jogadores.");
            }
        } catch (IllegalArgumentException e) {
            source.sendPlainMessage("Game ID inválido: " + gameStr);
        }
    }

    private void metrics(CommandSource source) {
        source.sendPlainMessage("=== BigBangHub Metrics ===");
        source.sendPlainMessage("Registrations: " + plugin.registrationsCount());
        source.sendPlainMessage("Heartbeats: " + plugin.heartbeatsReceivedCount() + " received, "
                + plugin.heartbeatsRejectedCount() + " rejected");
        source.sendPlainMessage("Matches: " + plugin.matchesCreatedCount() + " created, "
                + plugin.matchesStartedCount() + " started, "
                + plugin.matchesFinishedCount() + " finished, "
                + plugin.matchesAbortedCount() + " aborted");
        source.sendPlainMessage("Admissions: " + plugin.admissionsAcceptedCount() + " accepted, "
                + plugin.admissionsRejectedCount() + " rejected");
        source.sendPlainMessage("Routing: " + plugin.routingAttemptsCount() + " attempts, "
                + plugin.routingFailuresCount() + " failures");
        source.sendPlainMessage("Transfers: " + plugin.transfersInitiatedCount() + " initiated, "
                + plugin.transfersSucceededCount() + " succeeded, "
                + plugin.transfersFailedCount() + " failed, "
                + plugin.returnFailuresCount() + " return failures");
        source.sendPlainMessage("Reservation Expirations: " + plugin.reservationExpirationsCount());
        source.sendPlainMessage("Active Reservations: " + plugin.reservationService().activeCount());
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        if (queueCommand) {
            if (args.length <= 1) {
                return filterPrefix(List.of("join", "leave", "status"), args.length == 0 ? "" : args[0]);
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("join")) {
                List<String> gameNames = plugin.games().games().stream().map(g -> g.id().value()).toList();
                return filterPrefix(gameNames, args[1]);
            }
            return List.of();
        }

        if (args.length <= 1) {
            return filterPrefix(List.of("status", "instances", "instance", "matches", "match", "return", "queues", "queue", "metrics", "reload", "version"),
                    args.length == 0 ? "" : args[0]);
        }
        if (args.length == 2) {
            if (args[0].equalsIgnoreCase("instance")) {
                List<String> ids = plugin.instances().instances().stream().map(i -> i.instanceId().value()).toList();
                return filterPrefix(ids, args[1]);
            }
            if (args[0].equalsIgnoreCase("match")) {
                List<String> matchIds = plugin.matchRegistry().activeMatches().stream().map(m -> m.matchId().value()).toList();
                return filterPrefix(matchIds, args[1]);
            }
            if (args[0].equalsIgnoreCase("return")) {
                List<String> playerNames = plugin.proxy().getAllPlayers().stream().map(Player::getUsername).toList();
                return filterPrefix(playerNames, args[1]);
            }
            if (args[0].equalsIgnoreCase("queue")) {
                List<String> gameNames = plugin.games().games().stream().map(g -> g.id().value()).toList();
                return filterPrefix(gameNames, args[1]);
            }
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("match")) {
            return filterPrefix(List.of("abort"), args[2]);
        }
        return List.of();
    }

    private List<String> filterPrefix(List<String> items, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> matched = new ArrayList<>();
        for (String item : items) {
            if (item.toLowerCase(Locale.ROOT).startsWith(lower)) matched.add(item);
        }
        return matched;
    }

    private boolean admin(CommandSource source, String permission) {
        if (source.hasPermission(permission) || source.hasPermission("bigbanghub.admin")) return true;
        deny(source);
        return false;
    }

    private void deny(CommandSource source) {
        source.sendPlainMessage("Você não tem permissão.");
    }
}
