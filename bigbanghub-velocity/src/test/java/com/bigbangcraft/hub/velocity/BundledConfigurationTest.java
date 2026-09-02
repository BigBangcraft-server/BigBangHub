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

    @Test
    void sameAddressCorrectlyComparesResolvedAndUnresolvedAddresses() throws Exception {
        java.net.InetSocketAddress resolved = new java.net.InetSocketAddress(java.net.InetAddress.getByName("10.8.0.2"), 25567);
        java.net.InetSocketAddress unresolved = java.net.InetSocketAddress.createUnresolved("10.8.0.2", 25567);

        // Standard equals fails due to isUnresolved mismatch
        org.junit.jupiter.api.Assertions.assertNotEquals(resolved, unresolved);

        // BigBangHub's sameAddress must match them
        org.junit.jupiter.api.Assertions.assertTrue(BigBangHubVelocityPlugin.sameAddress(resolved, unresolved));
        org.junit.jupiter.api.Assertions.assertTrue(BigBangHubVelocityPlugin.sameAddress(unresolved, resolved));

        // Different ports or hosts must not match
        java.net.InetSocketAddress differentPort = java.net.InetSocketAddress.createUnresolved("10.8.0.2", 25568);
        java.net.InetSocketAddress differentHost = java.net.InetSocketAddress.createUnresolved("10.8.0.3", 25567);
        org.junit.jupiter.api.Assertions.assertFalse(BigBangHubVelocityPlugin.sameAddress(resolved, differentPort));
        org.junit.jupiter.api.Assertions.assertFalse(BigBangHubVelocityPlugin.sameAddress(resolved, differentHost));
    }
}
