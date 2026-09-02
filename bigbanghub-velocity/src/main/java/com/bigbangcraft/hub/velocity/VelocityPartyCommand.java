package com.bigbangcraft.hub.velocity;

import com.bigbangcraft.hub.api.PartyException;
import com.bigbangcraft.hub.api.PartyId;
import com.bigbangcraft.hub.api.PartyInvite;
import com.bigbangcraft.hub.api.PartyMember;
import com.bigbangcraft.hub.api.PartyRole;
import com.bigbangcraft.hub.api.PartyService;
import com.bigbangcraft.hub.api.PartySnapshot;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

final class VelocityPartyCommand implements SimpleCommand {
    private final PartyService parties;
    private final ProxyServer proxy;

    VelocityPartyCommand(BigBangHubVelocityPlugin plugin) {
        this(plugin.parties(), plugin.proxyServer());
    }

    VelocityPartyCommand(PartyService parties, ProxyServer proxy) {
        this.parties = Objects.requireNonNull(parties, "parties");
        this.proxy = Objects.requireNonNull(proxy, "proxy");
    }

    @Override
    public void execute(Invocation invocation) {
        if (!(invocation.source() instanceof Player player)) {
            invocation.source().sendPlainMessage("Comandos de Party só podem ser executados por jogadores.");
            return;
        }

        String[] args = invocation.arguments();
        if (args.length == 0) {
            showPartyStatus(player);
            return;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "invite" -> handleInvite(player, args);
            case "accept" -> handleAccept(player, args);
            case "decline" -> handleDecline(player, args);
            case "leave" -> handleLeave(player);
            case "kick" -> handleKick(player, args);
            case "leader" -> handleLeader(player, args);
            case "disband" -> handleDisband(player);
            case "list" -> handleList(player);
            default -> player.sendPlainMessage("Use: /party <invite|accept|decline|leave|kick|leader|disband|list>");
        }
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length <= 1) {
            String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            List<String> options = List.of("invite", "accept", "decline", "leave", "kick", "leader", "disband", "list");
            return options.stream().filter(s -> s.startsWith(prefix)).toList();
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        String prefix = args[1].toLowerCase(Locale.ROOT);

        if (sub.equals("invite")) {
            return proxy.getAllPlayers().stream()
                    .map(Player::getUsername)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .toList();
        }

        if ((sub.equals("kick") || sub.equals("leader")) && invocation.source() instanceof Player player) {
            return parties.partyOf(player.getUniqueId())
                    .map(p -> p.memberIds().stream()
                            .filter(id -> !id.equals(player.getUniqueId()))
                            .map(id -> proxy.getPlayer(id).map(Player::getUsername).orElse(id.toString()))
                            .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                            .toList())
                    .orElse(List.of());
        }

        return List.of();
    }

    private void showPartyStatus(Player player) {
        if (!player.hasPermission("bigbanghub.party.use")) {
            player.sendPlainMessage("§cVocê não tem permissão para usar party.");
            return;
        }

        Optional<PartySnapshot> partyOpt = parties.partyOf(player.getUniqueId());
        if (partyOpt.isEmpty()) {
            player.sendPlainMessage("§7Você não está em uma party. Use §b/party invite <jogador> §7para criar uma.");
            return;
        }

        PartySnapshot party = partyOpt.get();
        String leaderName = proxy.getPlayer(party.leader()).map(Player::getUsername).orElse(party.leader().toString());

        player.sendPlainMessage("§b§m----------------------------------------");
        player.sendPlainMessage("§b§lPARTY §8- §fStatus");
        player.sendPlainMessage("§7Líder: §e" + leaderName);
        player.sendPlainMessage("§7Jogadores: §f" + party.size() + "§7/§f" + parties.maxPartySize());
        player.sendPlainMessage("§7Estado: §a" + party.state());
        player.sendPlainMessage("§b§m----------------------------------------");
    }

    private void handleInvite(Player player, String[] args) {
        if (!player.hasPermission("bigbanghub.party.invite")) {
            player.sendPlainMessage("§cVocê não tem permissão para convidar jogadores.");
            return;
        }

        if (args.length < 2) {
            player.sendPlainMessage("§cUse: /party invite <jogador>");
            return;
        }

        String targetName = args[1];
        Player target = proxy.getPlayer(targetName).orElse(null);
        if (target == null || !target.isActive()) {
            player.sendPlainMessage("§cJogador '" + targetName + "' não encontrado ou offline.");
            return;
        }

        if (player.getUniqueId().equals(target.getUniqueId())) {
            player.sendPlainMessage("§cVocê não pode convidar a si mesmo.");
            return;
        }

        Optional<PartySnapshot> currentParty = parties.partyOf(player.getUniqueId());

        try {
            if (currentParty.isEmpty()) {
                currentParty = Optional.of(parties.createParty(player.getUniqueId()));
                player.sendPlainMessage("§aParty criada com sucesso!");
            }

            parties.invitePlayer(player.getUniqueId(), target.getUniqueId());
            player.sendPlainMessage("§aConvite de party enviado para §f" + target.getUsername() + "§a.");

            Component inviteMessage = Component.text()
                    .append(Component.text("§b§m----------------------------------------\n"))
                    .append(Component.text(player.getUsername(), NamedTextColor.YELLOW, TextDecoration.BOLD))
                    .append(Component.text(" convidou você para uma Party!\n", NamedTextColor.GRAY))
                    .append(Component.text(" [ACEITAR] ", NamedTextColor.GREEN, TextDecoration.BOLD)
                            .clickEvent(ClickEvent.runCommand("/party accept " + player.getUsername()))
                            .hoverEvent(HoverEvent.showText(Component.text("Clique para aceitar o convite"))))
                    .append(Component.text(" [RECUSAR] ", NamedTextColor.RED, TextDecoration.BOLD)
                            .clickEvent(ClickEvent.runCommand("/party decline " + player.getUsername()))
                            .hoverEvent(HoverEvent.showText(Component.text("Clique para recusar o convite"))))
                    .append(Component.text("\n§b§m----------------------------------------"))
                    .build();
            target.sendMessage(inviteMessage);
        } catch (PartyException ex) {
            player.sendPlainMessage("§c" + formatPartyError(ex));
        }
    }

    private void handleAccept(Player player, String[] args) {
        if (!player.hasPermission("bigbanghub.party.use")) {
            player.sendPlainMessage("§cVocê não tem permissão.");
            return;
        }

        if (parties.partyOf(player.getUniqueId()).isPresent()) {
            player.sendPlainMessage("§cVocê já está em uma party. Saia dela primeiro (/party leave).");
            return;
        }

        PartyId targetPartyId = null;
        if (args.length >= 2) {
            String inviterOrParty = args[1];
            Player inviter = proxy.getPlayer(inviterOrParty).orElse(null);
            if (inviter != null) {
                targetPartyId = parties.partyOf(inviter.getUniqueId()).map(PartySnapshot::partyId).orElse(null);
            }
            if (targetPartyId == null) {
                try {
                    targetPartyId = PartyId.fromString(inviterOrParty);
                } catch (Exception ignored) {
                }
            }
        } else {
            for (PartySnapshot p : parties.activeParties()) {
                if (p.invitedPlayers().containsKey(player.getUniqueId())) {
                    targetPartyId = p.partyId();
                    break;
                }
            }
        }

        if (targetPartyId == null) {
            player.sendPlainMessage("§cVocê não possui convites pendentes dessa party ou jogador.");
            return;
        }

        try {
            PartySnapshot party = parties.acceptInvite(player.getUniqueId(), targetPartyId);
            player.sendPlainMessage("§aVocê entrou na party!");

            broadcastToParty(party, "§e" + player.getUsername() + " §aentrou na party.", player.getUniqueId());
        } catch (PartyException ex) {
            player.sendPlainMessage("§c" + formatPartyError(ex));
        }
    }

    private void handleDecline(Player player, String[] args) {
        if (!player.hasPermission("bigbanghub.party.use")) {
            player.sendPlainMessage("§cVocê não tem permissão.");
            return;
        }

        PartyId targetPartyId = null;
        if (args.length >= 2) {
            String inviterOrParty = args[1];
            Player inviter = proxy.getPlayer(inviterOrParty).orElse(null);
            if (inviter != null) {
                targetPartyId = parties.partyOf(inviter.getUniqueId()).map(PartySnapshot::partyId).orElse(null);
            }
        } else {
            for (PartySnapshot p : parties.activeParties()) {
                if (p.invitedPlayers().containsKey(player.getUniqueId())) {
                    targetPartyId = p.partyId();
                    break;
                }
            }
        }

        if (targetPartyId != null) {
            parties.declineInvite(player.getUniqueId(), targetPartyId);
        }
        player.sendPlainMessage("§7Convite recusado.");
    }

    private void handleLeave(Player player) {
        if (!player.hasPermission("bigbanghub.party.use")) {
            player.sendPlainMessage("§cVocê não tem permissão.");
            return;
        }

        Optional<PartySnapshot> partyOpt = parties.partyOf(player.getUniqueId());
        if (partyOpt.isEmpty()) {
            player.sendPlainMessage("§cVocê não está em uma party.");
            return;
        }

        try {
            PartySnapshot partyAfter = parties.leaveParty(player.getUniqueId());
            player.sendPlainMessage("§cVocê saiu da party.");

            if (!partyAfter.state().isTerminal()) {
                broadcastToParty(partyAfter, "§e" + player.getUsername() + " §c- saiu da party.", player.getUniqueId());
            }
        } catch (PartyException ex) {
            player.sendPlainMessage("§c" + formatPartyError(ex));
        }
    }

    private void handleKick(Player player, String[] args) {
        if (!player.hasPermission("bigbanghub.party.invite")) {
            player.sendPlainMessage("§cVocê não tem permissão.");
            return;
        }

        if (args.length < 2) {
            player.sendPlainMessage("§cUse: /party kick <jogador>");
            return;
        }

        Optional<PartySnapshot> partyOpt = parties.partyOf(player.getUniqueId());
        if (partyOpt.isEmpty() || !partyOpt.get().isLeader(player.getUniqueId())) {
            player.sendPlainMessage("§cApenas o líder pode expulsar membros da party.");
            return;
        }

        String targetName = args[1];
        Player targetPlayer = proxy.getPlayer(targetName).orElse(null);
        UUID targetId = null;
        if (targetPlayer != null) {
            targetId = targetPlayer.getUniqueId();
        } else {
            for (UUID mId : partyOpt.get().memberIds()) {
                if (proxy.getPlayer(mId).map(p -> p.getUsername().equalsIgnoreCase(targetName)).orElse(false)) {
                    targetId = mId;
                    break;
                }
            }
        }

        if (targetId == null) {
            player.sendPlainMessage("§cJogador '" + targetName + "' não encontrado na sua party.");
            return;
        }

        try {
            PartySnapshot partyAfter = parties.kickPlayer(player.getUniqueId(), targetId);
            player.sendPlainMessage("§aVocê expulsou §f" + targetName + " §ada party.");
            if (targetPlayer != null && targetPlayer.isActive()) {
                targetPlayer.sendPlainMessage("§cVocê foi expulso da party.");
            }
            broadcastToParty(partyAfter, "§e" + targetName + " §cfoi expulso da party.", player.getUniqueId());
        } catch (PartyException ex) {
            player.sendPlainMessage("§c" + formatPartyError(ex));
        }
    }

    private void handleLeader(Player player, String[] args) {
        if (!player.hasPermission("bigbanghub.party.invite")) {
            player.sendPlainMessage("§cVocê não tem permissão.");
            return;
        }

        if (args.length < 2) {
            player.sendPlainMessage("§cUse: /party leader <jogador>");
            return;
        }

        Optional<PartySnapshot> partyOpt = parties.partyOf(player.getUniqueId());
        if (partyOpt.isEmpty() || !partyOpt.get().isLeader(player.getUniqueId())) {
            player.sendPlainMessage("§cApenas o líder atual pode transferir a liderança.");
            return;
        }

        String targetName = args[1];
        Player targetPlayer = proxy.getPlayer(targetName).orElse(null);
        UUID targetId = targetPlayer != null ? targetPlayer.getUniqueId() : null;
        if (targetId == null) {
            for (UUID mId : partyOpt.get().memberIds()) {
                if (proxy.getPlayer(mId).map(p -> p.getUsername().equalsIgnoreCase(targetName)).orElse(false)) {
                    targetId = mId;
                    break;
                }
            }
        }

        if (targetId == null) {
            player.sendPlainMessage("§cJogador '" + targetName + "' não encontrado na sua party.");
            return;
        }

        try {
            PartySnapshot partyAfter = parties.transferLeadership(player.getUniqueId(), targetId);
            broadcastToParty(partyAfter, "§6§lPARTY §8» §e" + targetName + " §aé o novo líder da party!", null);
        } catch (PartyException ex) {
            player.sendPlainMessage("§c" + formatPartyError(ex));
        }
    }

    private void handleDisband(Player player) {
        if (!player.hasPermission("bigbanghub.party.invite")) {
            player.sendPlainMessage("§cVocê não tem permissão.");
            return;
        }

        Optional<PartySnapshot> partyOpt = parties.partyOf(player.getUniqueId());
        if (partyOpt.isEmpty() || !partyOpt.get().isLeader(player.getUniqueId())) {
            player.sendPlainMessage("§cApenas o líder pode desfazer a party.");
            return;
        }

        PartySnapshot party = partyOpt.get();
        try {
            broadcastToParty(party, "§c§lPARTY §8» §cA party foi desfeita pelo líder.", null);
            parties.disbandParty(player.getUniqueId(), party.partyId());
        } catch (PartyException ex) {
            player.sendPlainMessage("§c" + formatPartyError(ex));
        }
    }

    private void handleList(Player player) {
        if (!player.hasPermission("bigbanghub.party.use")) {
            player.sendPlainMessage("§cVocê não tem permissão.");
            return;
        }

        Optional<PartySnapshot> partyOpt = parties.partyOf(player.getUniqueId());
        if (partyOpt.isEmpty()) {
            player.sendPlainMessage("§cVocê não está em uma party.");
            return;
        }

        PartySnapshot party = partyOpt.get();

        player.sendPlainMessage("§b§m----------------------------------------");
        player.sendPlainMessage("§b§lMEMBROS DA PARTY §7(" + party.size() + "/" + parties.maxPartySize() + ")");
        for (PartyMember member : party.members().values()) {
            String name = proxy.getPlayer(member.playerId()).map(Player::getUsername).orElse(member.playerId().toString());
            String roleStr = member.role() == PartyRole.LEADER ? "§6[Líder] " : "§7[Membro] ";
            player.sendPlainMessage(roleStr + "§f" + name);
        }
        player.sendPlainMessage("§b§m----------------------------------------");
    }

    private void broadcastToParty(PartySnapshot party, String message, UUID excludePlayerId) {
        for (UUID memberId : party.memberIds()) {
            if (excludePlayerId != null && excludePlayerId.equals(memberId)) continue;
            proxy.getPlayer(memberId).ifPresent(p -> p.sendPlainMessage(message));
        }
    }

    private String formatPartyError(PartyException ex) {
        return switch (ex.errorCode()) {
            case PLAYER_ALREADY_IN_PARTY -> "O jogador já está em uma party.";
            case PLAYER_NOT_IN_PARTY -> "Você não está em uma party.";
            case NOT_PARTY_LEADER -> "Apenas o líder pode executar esta ação.";
            case PARTY_FULL -> "A party atingiu a capacidade máxima.";
            case CANNOT_INVITE_SELF -> "Você não pode convidar a si mesmo.";
            case TARGET_ALREADY_IN_PARTY -> "O jogador convidado já está em uma party.";
            case INVITE_ALREADY_PENDING -> "Um convite já está pendente para este jogador.";
            case INVITE_NOT_FOUND -> "Convite não encontrado.";
            case INVITE_EXPIRED -> "O convite de party expirou.";
            case TARGET_NOT_IN_PARTY -> "O jogador não está na party.";
            case CANNOT_KICK_LEADER -> "O líder não pode expulsar a si mesmo.";
            case CANNOT_TRANSFER_TO_SELF -> "Você já é o líder da party.";
            case INVALID_PARTY_STATE, PARTY_MUTATION_LOCKED -> "A party está em partida ou na fila e não pode ser modificada.";
            case RATE_LIMITED -> "Aguarde alguns segundos antes de enviar outro convite.";
            case PARTY_NOT_FOUND -> "Party não encontrada.";
        };
    }
}
