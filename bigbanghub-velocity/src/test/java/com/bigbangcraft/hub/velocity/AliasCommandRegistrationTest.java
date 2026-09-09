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
        assertEquals("bedwars", snapshot.aliases().get("bedwars"));
        assertEquals("hg", snapshot.aliases().get("hg"));
        // 'campominado' intencionalmente SEM alias no proxy: o BigBangMinefield é
        // dono de /campominado no backend e o alias global interceptaria o comando.
        assertNull(snapshot.aliases().get("campominado"));
    }

    @Test
    void velocityAliasCommandClassExists() {
        assertNotNull(VelocityAliasCommand.class);
    }
}
