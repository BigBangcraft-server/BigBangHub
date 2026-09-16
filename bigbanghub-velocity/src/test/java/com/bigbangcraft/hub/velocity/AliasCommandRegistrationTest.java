package com.bigbangcraft.hub.velocity;

import com.bigbangcraft.hub.common.ConfigLoader;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AliasCommandRegistrationTest {

    @Test
    void bundledVelocityConfigMustContainQueueAliases() throws Exception {
        Path dir = Files.createTempDirectory("alias-test");
        for (String file : List.of("config.yml", "menus.yml", "games.yml", "servers.yml", "messages.yml")) {
            try (var input = getClass().getResourceAsStream("/" + file)) {
                assertNotNull(input, "Missing bundled " + file);
                Files.copy(input, dir.resolve(file));
            }
        }
        var snapshot = ConfigLoader.load(dir);
        // Nenhum alias de minigame no proxy: os plugins de backend são donos dos
        // comandos e um alias global os interceptaria.
        assertTrue(snapshot.aliases().isEmpty());
        assertNull(snapshot.aliases().get("campominado"));
        // 'bedwars' intencionalmente SEM alias no proxy: o MBedwars é dono de
        // /bedwars (e /bw) no backend bedwars.
        assertNull(snapshot.aliases().get("bedwars"));
        // 'hg' intencionalmente SEM alias no proxy: o BigBangHungerGames é dono
        // de /hg no backend hg (ex.: /hg setpos1).
        assertNull(snapshot.aliases().get("hg"));
    }

    @Test
    void velocityAliasCommandClassExists() {
        assertNotNull(VelocityAliasCommand.class);
    }
}
