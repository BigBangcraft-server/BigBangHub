package com.bigbangcraft.hub.common;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigLoaderTest {
    @Test
    void loadsValidSnapshotAndRejectsUnknownAction() throws Exception {
        Path dir = Files.createTempDirectory("bigbanghub-config");
        Files.writeString(dir.resolve("config.yml"), """
                proxy:
                  hub-server-name: hubminigame
                aliases:
                  campominado: campominado
                """);
        Files.writeString(dir.resolve("games.yml"), """
                games:
                  campominado:
                    display-name: Campo Minado
                    queue:
                      min-players: 2
                      max-players: 10
                """);
        Files.writeString(dir.resolve("servers.yml"), """
                servers:
                  campominado:
                    game: campominado
                    host: 10.8.0.2
                    port: 25567
                    state: WAITING
                    max-players: 10
                """);
        Files.writeString(dir.resolve("menus.yml"), """
                compass:
                  title: "Selecionar Minigame"
                  rows: 3
                  items:
                    campominado:
                      slot: 13
                      material: TNT
                      name: "Campo Minado"
                      action:
                        type: QUEUE
                        value: campominado
                """);
        Files.writeString(dir.resolve("messages.yml"), "messages:\n  queue-joined: entrou\n");

        assertEquals(1, ConfigLoader.load(dir).games().size());
        assertEquals("campominado", ConfigLoader.load(dir).aliases().get("campominado"));

        Files.writeString(dir.resolve("menus.yml"), """
                compass:
                  title: Menu
                  rows: 3
                  items:
                    campominado:
                      slot: 13
                      material: TNT
                      name: Campo
                      action:
                        type: SERVRE
                        value: campominado
                """);
        assertThrows(ConfigException.class, () -> ConfigLoader.load(dir));
    }

    @Test
    void loadsRoleInstanceAndRegistrySettings() throws Exception {
        Path dir = Files.createTempDirectory("bigbanghub-config-role");
        Files.writeString(dir.resolve("config.yml"), """
                server:
                  role: MINIGAME
                  instance:
                    instance-id: campominado-01
                    game-id: campominado
                    server-name: campominado-01
                    heartbeat:
                      interval: 3s
                    capacity:
                      min-players: 2
                      max-players: 10
                    accepting-players: true
                registry:
                  heartbeat-timeout: 12s
                  suspect-threshold: 6s
                  fallback-to-hub: true
                  allowed:
                    "campominado-*":
                      game-id: campominado
                routing:
                  reservation-ttl: 15s
                proxy:
                  hub-server-name: hubminigame
                """);
        Files.writeString(dir.resolve("games.yml"), """
                games:
                  campominado:
                    display-name: Campo Minado
                    queue:
                      min-players: 2
                      max-players: 10
                """);
        Files.writeString(dir.resolve("servers.yml"), """
                servers:
                  campominado-01:
                    game: campominado
                    host: 10.8.0.2
                    port: 25567
                    state: WAITING
                    max-players: 10
                """);
        Files.writeString(dir.resolve("menus.yml"), """
                compass:
                  title: "Menu"
                  rows: 3
                  items:
                    campominado:
                      slot: 13
                      material: TNT
                      name: "Campo"
                      action:
                        type: QUEUE
                        value: campominado
                """);
        Files.writeString(dir.resolve("messages.yml"), "messages:\n  test: ok\n");

        HubConfigSnapshot snapshot = ConfigLoader.load(dir);
        assertEquals(com.bigbangcraft.hub.api.ServerRole.MINIGAME, snapshot.role());
        assertTrue(snapshot.instance().isPresent());
        InstanceAgentSettings inst = snapshot.instance().get();
        assertEquals("campominado-01", inst.instanceId().value());
        assertEquals("campominado", inst.gameId().value());
        assertEquals(java.time.Duration.ofSeconds(3), inst.heartbeatInterval());
        assertEquals(2, inst.minPlayers());
        assertEquals(10, inst.maxPlayers());
        assertTrue(inst.acceptingPlayers());

        RegistrySettings reg = snapshot.registry();
        assertEquals(java.time.Duration.ofSeconds(12), reg.heartbeatTimeout());
        assertEquals(java.time.Duration.ofSeconds(6), reg.suspectThreshold());
        assertEquals(java.time.Duration.ofSeconds(15), reg.reservationTtl());
        assertTrue(reg.fallbackToHub());
        assertEquals(com.bigbangcraft.hub.api.GameId.of("campominado"), reg.allowedInstances().get("campominado-*"));
    }
}
