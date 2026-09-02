package com.bigbangcraft.hub.common;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
}
