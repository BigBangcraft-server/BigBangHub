package com.bigbangcraft.hub.velocity;

import com.bigbangcraft.hub.common.ConfigLoader;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BundledConfigurationTest {
    @Test
    void bundledVelocityConfigurationLoadsAsOneSnapshot() throws Exception {
        Path directory = Files.createTempDirectory("bigbanghub-velocity-config");
        for (String file : List.of("config.yml", "menus.yml", "games.yml", "servers.yml", "messages.yml")) {
            try (var input = getClass().getResourceAsStream("/" + file)) {
                Files.copy(input, directory.resolve(file));
            }
        }

        var snapshot = ConfigLoader.load(directory);
        assertEquals(3, snapshot.games().size());
        assertEquals(3, snapshot.servers().size());
    }
}
