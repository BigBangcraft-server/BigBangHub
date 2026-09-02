package com.bigbangcraft.hub.paper;

import com.bigbangcraft.hub.api.BigBangHubApi;
import com.bigbangcraft.hub.api.GameRegistry;
import com.bigbangcraft.hub.api.PlayerTransferService;
import com.bigbangcraft.hub.api.QueueService;
import com.bigbangcraft.hub.api.RoutingService;
import com.bigbangcraft.hub.api.ServerRegistry;
import com.bigbangcraft.hub.common.ConfigException;
import com.bigbangcraft.hub.common.ConfigLoader;
import com.bigbangcraft.hub.common.FillWaitingRoutingService;
import com.bigbangcraft.hub.common.HubConfigSnapshot;
import com.bigbangcraft.hub.common.InMemoryGameRegistry;
import com.bigbangcraft.hub.common.InMemoryServerRegistry;
import com.bigbangcraft.hub.common.ProtocolCodec;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public final class BigBangHubPaperPlugin extends JavaPlugin implements BigBangHubApi {
    private final AtomicReference<HubConfigSnapshot> config = new AtomicReference<>();
    private final AtomicReference<GameRegistry> games = new AtomicReference<>();
    private final AtomicReference<ServerRegistry> servers = new AtomicReference<>();
    private final AtomicReference<RoutingService> routing = new AtomicReference<>();
    private final AtomicReference<CompassMenuController> menu = new AtomicReference<>();
    private VelocityBridge bridge;
    private PaperQueueService queues;
    private PaperTransferService transfers;

    @Override
    public void onEnable() {
        saveDefaults();
        try {
            HubConfigSnapshot snapshot = load();
            ProtocolCodec codec = codec(snapshot);
            bridge = new VelocityBridge(this, snapshot.proxy().channel(), codec);
            queues = new PaperQueueService(bridge);
            transfers = new PaperTransferService(bridge);
            install(snapshot);
        } catch (ConfigException | IllegalArgumentException exception) {
            getLogger().severe("BigBangHub failed to enable: " + exception.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getServer().getServicesManager().register(BigBangHubApi.class, this, this, ServicePriority.Normal);
        getCommand("bbhub").setExecutor(new HubCommand(this));
        getCommand("bbhub").setTabCompleter(new HubCommand(this));
        getCommand("queue").setExecutor(new QueueCommand(this));
        getCommand("queue").setTabCompleter(new QueueCommand(this));
        getServer().getPluginManager().registerEvents(new PaperListener(this), this);
        getLogger().info("BigBangHub Paper enabled with " + games().games().size() + " games");
    }

    @Override
    public void onDisable() {
        if (bridge != null) bridge.close();
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
            CompassMenuController nextMenu = new CompassMenuController(this, next);
            install(next, nextMenu);
            sender.sendMessage("§aBigBangHub configuration reloaded.");
            getLogger().info("Reloaded configuration: " + next.games().size() + " games, "
                    + next.compass().entries().size() + " compass entries");
        } catch (ConfigException | IllegalArgumentException exception) {
            sender.sendMessage("§cReload rejected: " + exception.getMessage());
            getLogger().warning("Keeping previous configuration: " + exception.getMessage());
        }
    }

    private void install(HubConfigSnapshot snapshot) {
        install(snapshot, new CompassMenuController(this, snapshot));
    }

    private void install(HubConfigSnapshot snapshot, CompassMenuController nextMenu) {
        GameRegistry nextGames = new InMemoryGameRegistry(snapshot.games());
        ServerRegistry nextServers = new InMemoryServerRegistry(snapshot.servers());
        RoutingService nextRouting = new FillWaitingRoutingService(nextGames, nextServers);
        games.set(nextGames);
        servers.set(nextServers);
        routing.set(nextRouting);
        menu.set(nextMenu);
        config.set(snapshot);
    }

    private HubConfigSnapshot load() throws ConfigException {
        return ConfigLoader.load(dataFolder());
    }

    private ProtocolCodec codec(HubConfigSnapshot snapshot) throws ConfigException {
        byte[] secret = ProtocolCodec.secretFromEnvironment(snapshot.proxy().sharedSecretEnvironment(), snapshot.proxy().requireHmac());
        return new ProtocolCodec(secret, snapshot.proxy().maxPayloadBytes(), snapshot.proxy().requireHmac());
    }

    private Path dataFolder() {
        if (!getDataFolder().isDirectory() && !getDataFolder().mkdirs()) {
            throw new IllegalStateException("Unable to create plugin data directory");
        }
        return getDataFolder().toPath();
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

    @Override public GameRegistry games() { return games.get(); }
    @Override public ServerRegistry servers() { return servers.get(); }
    @Override public QueueService queues() { return queues; }
    @Override public RoutingService routing() { return routing.get(); }
    @Override public PlayerTransferService transfers() { return transfers; }
    @Override public void addQueueListener(java.util.function.Consumer<com.bigbangcraft.hub.api.QueueEvent> listener) { queues.addListener(listener); }
    @Override public void removeQueueListener(java.util.function.Consumer<com.bigbangcraft.hub.api.QueueEvent> listener) { queues.removeListener(listener); }
}
