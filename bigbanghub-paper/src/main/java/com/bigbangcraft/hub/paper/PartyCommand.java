package com.bigbangcraft.hub.paper;

import com.bigbangcraft.hub.api.PartyException;
import com.bigbangcraft.hub.api.PartyId;
import com.bigbangcraft.hub.api.PartyInvite;
import com.bigbangcraft.hub.api.PartyMember;
import com.bigbangcraft.hub.api.PartyRole;
import com.bigbangcraft.hub.api.PartyService;
import com.bigbangcraft.hub.api.PartySnapshot;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

final class PartyCommand implements CommandExecutor, TabCompleter {
    private final BigBangHubPaperPlugin plugin;

    PartyCommand(BigBangHubPaperPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cEste comando só pode ser usado por jogadores.");
            return true;
        }

        if (args.length == 0) {
            showStatus(player);
            return true;
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
            default -> player.sendMessage("§7Use: /party <invite|accept|decline|leave|kick|leader|disband|list>");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return List.of("invite", "accept", "decline", "leave", "kick", "leader", "disband", "list").stream()
                    .filter(s -> s.startsWith(prefix)).toList();
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("invite")) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .toList();
        }

        if (args.length == 2 && (args[0].equalsIgnoreCase("kick") || args[0].equalsIgnoreCase("leader"))
                && sender instanceof Player player) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return plugin.parties().partyOf(player.getUniqueId())
                    .map(p -> p.memberIds().stream()
                            .filter(id -> !id.equals(player.getUniqueId()))
                            .map(id -> Optional.ofNullable(Bukkit.getPlayer(id)).map(Player::getName).orElse(id.toString()))
                            .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                            .toList())
                    .orElse(List.of());
        }

        return List.of();
    }

    private void showStatus(Player player) {
        if (!player.hasPermission("bigbanghub.party.use")) {
            deny(player);
            return;
        }

        PartyService service = plugin.parties();
        Optional<PartySnapshot> partyOpt = service.partyOf(player.getUniqueId());
        if (partyOpt.isEmpty()) {
            player.sendMessage("§7Você não está em uma party. Use §b/party invite <jogador> §7para criar uma.");
            return;
        }

        PartySnapshot party = partyOpt.get();
        String leaderName = Optional.ofNullable(Bukkit.getPlayer(party.leader()))
                .map(Player::getName).orElse(party.leader().toString());

        player.sendMessage("§b§m----------------------------------------");
        player.sendMessage("§b§lPARTY §8- §fStatus");
        player.sendMessage("§7Líder: §e" + leaderName);
        player.sendMessage("§7Jogadores: §f" + party.size() + "§7/§f" + service.maxPartySize());
        player.sendMessage("§7Estado: §a" + party.state());
        player.sendMessage("§b§m----------------------------------------");
    }

    private void handleInvite(Player player, String[] args) {
        if (!player.hasPermission("bigbanghub.party.invite")) {
            deny(player);
            return;
        }

        if (args.length < 2) {
            player.sendMessage("§cUse: /party invite <jogador>");
            return;
        }

        String targetName = args[1];
        Player target = Bukkit.getPlayer(targetName);
        if (target == null || !target.isOnline()) {
            player.sendMessage("§cJogador '" + targetName + "' não encontrado ou offline.");
            return;
        }

        if (player.getUniqueId().equals(target.getUniqueId())) {
            player.sendMessage("§cVocê não pode convidar a si mesmo.");
            return;
        }

        PartyService service = plugin.parties();
        Optional<PartySnapshot> currentParty = service.partyOf(player.getUniqueId());

        try {
            if (currentParty.isEmpty()) {
                currentParty = Optional.of(service.createParty(player.getUniqueId()));
                player.sendMessage("§aParty criada com sucesso!");
            }

            PartyInvite invite = service.invitePlayer(player.getUniqueId(), target.getUniqueId());
            player.sendMessage("§aConvite de party enviado para §f" + target.getName() + "§a.");

            Component inviteMsg = Component.text()
                    .append(Component.text("§b§m----------------------------------------\n"))
                    .append(Component.text(player.getName(), NamedTextColor.YELLOW, TextDecoration.BOLD))
                    .append(Component.text(" convidou você para uma Party!\n", NamedTextColor.GRAY))
                    .append(Component.text(" [ACEITAR] ", NamedTextColor.GREEN, TextDecoration.BOLD)
                            .clickEvent(ClickEvent.runCommand("/party accept " + player.getName()))
                            .hoverEvent(HoverEvent.showText(Component.text("Clique para aceitar o convite"))))
                    .append(Component.text(" [RECUSAR] ", NamedTextColor.RED, TextDecoration.BOLD)
                            .clickEvent(ClickEvent.runCommand("/party decline " + player.getName()))
                            .hoverEvent(HoverEvent.showText(Component.text("Clique para recusar o convite"))))
                    .append(Component.text("\n§b§m----------------------------------------"))
                    .build();
            target.sendMessage(inviteMsg);
        } catch (PartyException ex) {
            player.sendMessage("§c" + formatError(ex));
        }
    }

    private void handleAccept(Player player, String[] args) {
        if (!player.hasPermission("bigbanghub.party.use")) {
            deny(player);
            return;
        }

        PartyService service = plugin.parties();
        if (service.partyOf(player.getUniqueId()).isPresent()) {
            player.sendMessage("§cVocê já está em uma party. Saia dela primeiro (/party leave).");
            return;
        }

        PartyId targetPartyId = null;
        if (args.length >= 2) {
            String inviterOrParty = args[1];
            Player inviter = Bukkit.getPlayer(inviterOrParty);
            if (inviter != null) {
                targetPartyId = service.partyOf(inviter.getUniqueId()).map(PartySnapshot::partyId).orElse(null);
            }
            if (targetPartyId == null) {
                try {
                    targetPartyId = PartyId.fromString(inviterOrParty);
                } catch (Exception ignored) {
                }
            }
        } else {
            for (PartySnapshot p : service.activeParties()) {
                if (p.invitedPlayers().containsKey(player.getUniqueId())) {
                    targetPartyId = p.partyId();
                    break;
                }
            }
        }

        if (targetPartyId == null) {
            player.sendMessage("§cVocê não possui convites pendentes dessa party ou jogador.");
            return;
        }

        try {
            PartySnapshot party = service.acceptInvite(player.getUniqueId(), targetPartyId);
            player.sendMessage("§aVocê entrou na party!");
            broadcastParty(party, "§e" + player.getName() + " §aentrou na party.", player.getUniqueId());
        } catch (PartyException ex) {
            player.sendMessage("§c" + formatError(ex));
        }
    }

    private void handleDecline(Player player, String[] args) {
        if (!player.hasPermission("bigbanghub.party.use")) {
            deny(player);
            return;
        }

        PartyService service = plugin.parties();
        PartyId targetPartyId = null;
        if (args.length >= 2) {
            Player inviter = Bukkit.getPlayer(args[1]);
            if (inviter != null) {
                targetPartyId = service.partyOf(inviter.getUniqueId()).map(PartySnapshot::partyId).orElse(null);
            }
        } else {
            for (PartySnapshot p : service.activeParties()) {
                if (p.invitedPlayers().containsKey(player.getUniqueId())) {
                    targetPartyId = p.partyId();
                    break;
                }
            }
        }

        if (targetPartyId != null) {
            service.declineInvite(player.getUniqueId(), targetPartyId);
        }
        player.sendMessage("§7Convite recusado.");
    }

    private void handleLeave(Player player) {
        if (!player.hasPermission("bigbanghub.party.use")) {
            deny(player);
            return;
        }

        PartyService service = plugin.parties();
        Optional<PartySnapshot> partyOpt = service.partyOf(player.getUniqueId());
        if (partyOpt.isEmpty()) {
            player.sendMessage("§cVocê não está em uma party.");
            return;
        }

        try {
            PartySnapshot partyAfter = service.leaveParty(player.getUniqueId());
            player.sendMessage("§cVocê saiu da party.");
            if (!partyAfter.state().isTerminal()) {
                broadcastParty(partyAfter, "§e" + player.getName() + " §c- saiu da party.", player.getUniqueId());
            }
        } catch (PartyException ex) {
            player.sendMessage("§c" + formatError(ex));
        }
    }

    private void handleKick(Player player, String[] args) {
        if (!player.hasPermission("bigbanghub.party.invite")) {
            deny(player);
            return;
        }

        if (args.length < 2) {
            player.sendMessage("§cUse: /party kick <jogador>");
            return;
        }

        PartyService service = plugin.parties();
        Optional<PartySnapshot> partyOpt = service.partyOf(player.getUniqueId());
        if (partyOpt.isEmpty() || !partyOpt.get().isLeader(player.getUniqueId())) {
            player.sendMessage("§cApenas o líder pode expulsar membros da party.");
            return;
        }

        String targetName = args[1];
        Player targetPlayer = Bukkit.getPlayer(targetName);
        UUID targetId = targetPlayer != null ? targetPlayer.getUniqueId() : null;

        if (targetId == null) {
            for (UUID mId : partyOpt.get().memberIds()) {
                Player p = Bukkit.getPlayer(mId);
                if (p != null && p.getName().equalsIgnoreCase(targetName)) {
                    targetId = mId;
                    break;
                }
            }
        }

        if (targetId == null) {
            player.sendMessage("§cJogador '" + targetName + "' não encontrado na sua party.");
            return;
        }

        try {
            PartySnapshot partyAfter = service.kickPlayer(player.getUniqueId(), targetId);
            player.sendMessage("§aVocê expulsou §f" + targetName + " §ada party.");
            if (targetPlayer != null && targetPlayer.isOnline()) {
                targetPlayer.sendMessage("§cVocê foi expulso da party.");
            }
            broadcastParty(partyAfter, "§e" + targetName + " §cfoi expulso da party.", player.getUniqueId());
        } catch (PartyException ex) {
            player.sendMessage("§c" + formatError(ex));
        }
    }

    private void handleLeader(Player player, String[] args) {
        if (!player.hasPermission("bigbanghub.party.invite")) {
            deny(player);
            return;
        }

        if (args.length < 2) {
            player.sendMessage("§cUse: /party leader <jogador>");
            return;
        }

        PartyService service = plugin.parties();
        Optional<PartySnapshot> partyOpt = service.partyOf(player.getUniqueId());
        if (partyOpt.isEmpty() || !partyOpt.get().isLeader(player.getUniqueId())) {
            player.sendMessage("§cApenas o líder atual pode transferir a liderança.");
            return;
        }

        String targetName = args[1];
        Player targetPlayer = Bukkit.getPlayer(targetName);
        UUID targetId = targetPlayer != null ? targetPlayer.getUniqueId() : null;

        if (targetId == null) {
            for (UUID mId : partyOpt.get().memberIds()) {
                Player p = Bukkit.getPlayer(mId);
                if (p != null && p.getName().equalsIgnoreCase(targetName)) {
                    targetId = mId;
                    break;
                }
            }
        }

        if (targetId == null) {
            player.sendMessage("§cJogador '" + targetName + "' não encontrado na sua party.");
            return;
        }

        try {
            PartySnapshot partyAfter = service.transferLeadership(player.getUniqueId(), targetId);
            broadcastParty(partyAfter, "§6§lPARTY §8» §e" + targetName + " §aé o novo líder da party!", null);
        } catch (PartyException ex) {
            player.sendMessage("§c" + formatError(ex));
        }
    }

    private void handleDisband(Player player) {
        if (!player.hasPermission("bigbanghub.party.invite")) {
            deny(player);
            return;
        }

        PartyService service = plugin.parties();
        Optional<PartySnapshot> partyOpt = service.partyOf(player.getUniqueId());
        if (partyOpt.isEmpty() || !partyOpt.get().isLeader(player.getUniqueId())) {
            player.sendMessage("§cApenas o líder pode desfazer a party.");
            return;
        }

        PartySnapshot party = partyOpt.get();
        try {
            broadcastParty(party, "§c§lPARTY §8» §cA party foi desfeita pelo líder.", null);
            service.disbandParty(player.getUniqueId(), party.partyId());
        } catch (PartyException ex) {
            player.sendMessage("§c" + formatError(ex));
        }
    }

    private void handleList(Player player) {
        if (!player.hasPermission("bigbanghub.party.use")) {
            deny(player);
            return;
        }

        PartyService service = plugin.parties();
        Optional<PartySnapshot> partyOpt = service.partyOf(player.getUniqueId());
        if (partyOpt.isEmpty()) {
            player.sendMessage("§cVocê não está em uma party.");
            return;
        }

        PartySnapshot party = partyOpt.get();
        player.sendMessage("§b§m----------------------------------------");
        player.sendMessage("§b§lMEMBROS DA PARTY §7(" + party.size() + "/" + service.maxPartySize() + ")");
        for (PartyMember member : party.members().values()) {
            String name = Optional.ofNullable(Bukkit.getPlayer(member.playerId()))
                    .map(Player::getName).orElse(member.playerId().toString());
            String roleStr = member.role() == PartyRole.LEADER ? "§6[Líder] " : "§7[Membro] ";
            player.sendMessage(roleStr + "§f" + name);
        }
        player.sendMessage("§b§m----------------------------------------");
    }

    private void broadcastParty(PartySnapshot party, String message, UUID exclude) {
        for (UUID memberId : party.memberIds()) {
            if (exclude != null && exclude.equals(memberId)) continue;
            Player p = Bukkit.getPlayer(memberId);
            if (p != null && p.isOnline()) p.sendMessage(message);
        }
    }

    private void deny(Player player) {
        player.sendMessage("§cVocê não tem permissão.");
    }

    private String formatError(PartyException ex) {
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
