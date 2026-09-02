package com.bigbangcraft.hub.paper;

import com.bigbangcraft.hub.api.BigBangHubApi;
import com.bigbangcraft.hub.api.GameRegistry;
import com.bigbangcraft.hub.api.InstanceRegistry;
import com.bigbangcraft.hub.api.InstanceService;
import com.bigbangcraft.hub.api.MatchEvent;
import com.bigbangcraft.hub.api.MatchManager;
import com.bigbangcraft.hub.api.PartyEvent;
import com.bigbangcraft.hub.api.PartyService;
import com.bigbangcraft.hub.api.PlayerTransferService;
import com.bigbangcraft.hub.api.QueueEvent;
import com.bigbangcraft.hub.api.QueueService;
import com.bigbangcraft.hub.api.RoutingService;
import com.bigbangcraft.hub.api.ServerRegistry;
import com.bigbangcraft.hub.api.ServerRole;
import com.bigbangcraft.hub.common.PartyEventBus;
import com.bigbangcraft.hub.common.CompassMenu;
import com.bigbangcraft.hub.common.ConfigException;
import com.bigbangcraft.hub.common.ConfigLoader;
import com.bigbangcraft.hub.common.HubConfigSnapshot;
import com.bigbangcraft.hub.common.InMemoryGameRegistry;
import com.bigbangcraft.hub.common.InMemoryServerRegistry;
import com.bigbangcraft.hub.common.InstanceAgentSettings;
import com.bigbangcraft.hub.common.ProtocolCodec;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public final class BigBangHubPaperPlugin extends JavaPlugin implements BigBangHubApi {
    private final AtomicReference<HubConfigSnapshot> config = new AtomicReference<>();
    private final AtomicReference<GameRegistry> games = new AtomicReference<>();
    private final AtomicReference<ServerRegistry> servers = new AtomicReference<>();
    private final AtomicReference<RoutingService> routing = new AtomicReference<>();
    private final AtomicReference<CompassMenuController> menu = new AtomicReference<>();
    private VelocityBridge bridge;
    private PaperQueueService queues;
    private PaperTransferService transfers;
    private PaperInstanceAgent instanceAgent;
    private PaperMatchManager matchManager;
    private PaperPartyService partyService;
    private PartyEventBus partyEventBus;

    @Override
    public void onEnable() {
        saveDefaults();
        HubConfigSnapshot snapshot;
        try {
            snapshot = load();
            bridge = new VelocityBridge(this, snapshot.proxy().channel(), codec(snapshot));
        } catch (ConfigException | IllegalArgumentException exception) {
            getLogger().severe("Unable to load BigBangHub configuration: " + exception.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        queues = new PaperQueueService(bridge);
        transfers = new PaperTransferService(bridge);
        partyEventBus = new PartyEventBus();
        partyService = new PaperPartyService(bridge, snapshot.party(), partyEventBus);
        install(snapshot);
        getServer().getServicesManager().register(BigBangHubApi.class, this, this, ServicePriority.Normal);
        getCommand("bbhub").setExecutor(new HubCommand(this));
        getCommand("bbhub").setTabCompleter(new HubCommand(this));
        getCommand("party").setExecutor(new PartyCommand(this));
        getCommand("party").setTabCompleter(new PartyCommand(this));
        if (getCommand("reconnect") != null) {
            getCommand("reconnect").setExecutor((sender, cmd, label, args) -> {
                if (sender instanceof Player p) {
                    p.sendMessage("§eEncaminhando pedido de reconexão ao proxy...");
                }
                return true;
            });
        }
        if (getCommand("playagain") != null) {
            getCommand("playagain").setExecutor((sender, cmd, label, args) -> {
                if (sender instanceof Player p) {
                    p.sendMessage("§eEncaminhando solicitação de Jogar Novamente ao proxy...");
                }
                return true;
            });
        }
        if (getCommand("rematch") != null) {
            getCommand("rematch").setExecutor((sender, cmd, label, args) -> {
                if (sender instanceof Player p) {
                    p.sendMessage("§eEncaminhando voto de revanche ao proxy...");
                }
                return true;
            });
        }

        if (snapshot.role() == ServerRole.HUB) {
            getCommand("queue").setExecutor(new QueueCommand(this));
            getCommand("queue").setTabCompleter(new QueueCommand(this));
            getServer().getPluginManager().registerEvents(new PaperListener(this), this);
            getLogger().info("BigBangHub Paper enabled in HUB role with " + games().games().size() + " games");
        } else if (snapshot.role() == ServerRole.MINIGAME || snapshot.instance().isPresent()) {
            InstanceAgentSettings agentSettings = snapshot.instance().orElseGet(() ->
                    InstanceAgentSettings.of(getServer().getName(), "default", getServer().getName(),
                            java.time.Duration.ofSeconds(3), 2, 10));
            instanceAgent = new PaperInstanceAgent(this, bridge, agentSettings);
            matchManager = new PaperMatchManager(this, bridge, transfers, instanceAgent, snapshot.match().autoCreateMatch());
            instanceAgent.start();
            getServer().getPluginManager().registerEvents(new PaperInstanceListener(this, instanceAgent), this);
            getLogger().info("BigBangHub Paper enabled in MINIGAME agent role for " + agentSettings.instanceId()
                    + " (" + agentSettings.gameId() + ")");
        } else {
            getLogger().info("BigBangHub Paper enabled in GENERIC role");
        }
    }

    @Override
    public void onDisable() {
        if (matchManager != null) {
            matchManager.currentMatch().ifPresent(m -> m.abort("Server shutting down"));
        }
        if (instanceAgent != null) instanceAgent.stop();
        if (bridge != null) bridge.close();
        if (partyService != null) partyService.clear();
        getServer().getServicesManager().unregister(BigBangHubApi.class, this);
        getServer().getScheduler().cancelTasks(this);
    }

    void reload(CommandSender sender) {
        try {
            HubConfigSnapshot next = load();
            ProtocolCodec nextCodec = codec(next);
            HubConfigSnapshot current = configSnapshot();
            if (!current.proxy().channel().equals(next.proxy().channel())
                    || current.proxy().protocolVersion() != next.proxy().protocolVersion()
                    || current.proxy().maxPayloadBytes() != next.proxy().maxPayloadBytes()) {
                throw new ConfigException("proxy channel, protocol version and payload limit require a restart");
            }
            if (!codec(current).hasSameAuthentication(nextCodec)) {
                throw new ConfigException("protocol authentication changes require a restart");
            }
            install(next);
            sender.sendMessage("BigBangHub configuration reloaded.");
        } catch (ConfigException | IllegalArgumentException exception) {
            sender.sendMessage("Reload rejected; keeping current configuration: " + exception.getMessage());
            getLogger().warning("Failed reload: " + exception.getMessage());
        }
    }

    private void install(HubConfigSnapshot snapshot) {
        GameRegistry nextGames = new InMemoryGameRegistry(snapshot.games());
        ServerRegistry nextServers = new InMemoryServerRegistry(snapshot.servers());
        RoutingService nextRouting = new com.bigbangcraft.hub.common.FillWaitingRoutingService(nextGames, nextServers);
        games.set(nextGames);
        servers.set(nextServers);
        routing.set(nextRouting);
        config.set(snapshot);
        menu.set(new CompassMenuController(this, snapshot));
    }

    private HubConfigSnapshot load() throws ConfigException {
        return ConfigLoader.load(dataFolder());
    }

    private Path dataFolder() {
        File folder = getDataFolder();
        if (!folder.exists() && !folder.mkdirs()) {
            throw new IllegalStateException("Cannot create plugin data folder " + folder);
        }
        return folder.toPath();
    }

    private ProtocolCodec codec(HubConfigSnapshot snapshot) throws ConfigException {
        byte[] secret = ProtocolCodec.secretFromEnvironment(snapshot.proxy().sharedSecretEnvironment(), snapshot.proxy().requireHmac());
        return new ProtocolCodec(secret, snapshot.proxy().maxPayloadBytes(), snapshot.proxy().requireHmac());
    }

    private void saveDefaults() {
        dataFolder();
        saveResource("config.yml", false);
        saveResource("menus.yml", false);
        saveResource("games.yml", false);
        saveResource("servers.yml", false);
        saveResource("messages.yml", false);
    }

    HubConfigSnapshot configSnapshot() { return Objects.requireNonNull(config.get(), "plugin is not enabled"); }
    CompassMenuController menu() { return menu.get(); }
    public PaperInstanceAgent instanceAgent() { return instanceAgent; }
    public PaperMatchManager matchManager() { return matchManager; }
    public PaperPartyService partyService() { return partyService; }

    @Override public ServerRole role() { return configSnapshot().role(); }
    @Override public GameRegistry games() { return games.get(); }
    @Override public ServerRegistry servers() { return servers.get(); }
    @Override public InstanceRegistry instances() { return InstanceRegistry.empty(); }
    @Override public Optional<InstanceService> instance() { return Optional.ofNullable(instanceAgent); }
    @Override public MatchManager matches() { return matchManager != null ? matchManager : BigBangHubApi.super.matches(); }
    @Override public QueueService queues() { return queues; }
    @Override public PartyService parties() { return partyService; }
    @Override public RoutingService routing() { return routing.get(); }
    @Override public PlayerTransferService transfers() { return transfers; }
    @Override public void addQueueListener(Consumer<QueueEvent> listener) { queues.addListener(listener); }
    @Override public void removeQueueListener(Consumer<QueueEvent> listener) { queues.removeListener(listener); }
    @Override public void addMatchListener(Consumer<MatchEvent> listener) {
        if (matchManager != null) matchManager.eventBus().add(listener);
    }
    @Override public void removeMatchListener(Consumer<MatchEvent> listener) {
        if (matchManager != null) matchManager.eventBus().remove(listener);
    }
    @Override public void addPartyListener(Consumer<PartyEvent> listener) {
        if (partyEventBus != null) partyEventBus.add(listener);
    }
    @Override public void removePartyListener(Consumer<PartyEvent> listener) {
        if (partyEventBus != null) partyEventBus.remove(listener);
    }
}
