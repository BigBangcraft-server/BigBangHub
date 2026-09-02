package com.bigbangcraft.hub.velocity;

import com.bigbangcraft.hub.common.ConfigLoader;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AliasCommandRegistrationTest {

    @Test
    void bundledVelocityConfigMustContainCampominadoAlias() throws Exception {
        Path dir = Files.createTempDirectory("alias-test");
        for (String file : List.of("config.yml", "menus.yml", "games.yml", "servers.yml", "messages.yml")) {
            try (var input = getClass().getResourceAsStream("/" + file)) {
                assertNotNull(input, "Missing bundled " + file);
                Files.copy(input, dir.resolve(file));
            }
        }
        var snapshot = ConfigLoader.load(dir);
        assertEquals("campominado", snapshot.aliases().get("campominado"), "Bundled velocity config should map campominado alias to campominado game");
    }

    @Test
    void velocityAliasCommandClassExists() {
        assertNotNull(VelocityAliasCommand.class);
    }
}
