package com.bigbangcraft.hub.common;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class CompassHotfixRegressionTest {

    private String readPaperListener() throws Exception {
        Path[] candidates = new Path[]{
                Path.of("../bigbanghub-paper/src/main/java/com/bigbangcraft/hub/paper/PaperListener.java"),
                Path.of("bigbanghub-paper/src/main/java/com/bigbangcraft/hub/paper/PaperListener.java"),
                Path.of("/home/pedro/Documentos/java/BigBangHub/bigbanghub-paper/src/main/java/com/bigbangcraft/hub/paper/PaperListener.java")
        };
        for (Path p : candidates) if (Files.exists(p)) return Files.readString(p);
        throw new IllegalStateException("PaperListener.java not found");
    }

    @Test
    void compassHandlerMustNotUseIgnoreCancelledTrue() throws Exception {
        String src = readPaperListener();
        // Find all occurrences of onCompass and check its annotation
        int idx = src.indexOf("public void onCompass");
        assertTrue(idx > 0, "onCompass method must exist");
        String before = src.substring(Math.max(0, idx - 150), idx);
        assertTrue(before.contains("EventPriority.LOWEST"), "onCompass should use LOWEST priority");
        assertFalse(before.contains("ignoreCancelled"), "onCompass must not be ignoreCancelled=true");
        int aliasIdx = src.indexOf("public void onAlias");
        String aliasBefore = src.substring(Math.max(0, aliasIdx - 150), aliasIdx);
        assertTrue(aliasBefore.contains("EventPriority.LOWEST"), "onAlias should use LOWEST");
        assertFalse(aliasBefore.contains("ignoreCancelled"), "onAlias must not be ignoreCancelled=true");
    }

    @Test
    void inventoryClickHandlerMustAllowMenuEvenIfCancelled() throws Exception {
        String src = readPaperListener();
        int idx = src.indexOf("public void onInventoryClick");
        assertTrue(idx > 0);
        String before = src.substring(Math.max(0, idx - 150), idx);
        assertTrue(before.contains("LOWEST"), "onInventoryClick should be LOWEST");
        assertFalse(before.contains("ignoreCancelled"), "onInventoryClick must not be ignoreCancelled=true");
    }

    @Test
    void velocityAliasesMustContainQueueAliasesWithoutCampominado() throws Exception {
        Path[] candidates = new Path[]{
                Path.of("bigbanghub-velocity/src/main/resources/config.yml"),
                Path.of("../bigbanghub-velocity/src/main/resources/config.yml"),
                Path.of("/home/pedro/Documentos/java/BigBangHub/bigbanghub-velocity/src/main/resources/config.yml")
        };
        Path cfg = null;
        for (Path p : candidates) if (Files.exists(p)) { cfg = p; break; }
        if (cfg == null) throw new IllegalStateException("velocity config not found");
        String src = Files.readString(cfg);
        assertTrue(src.contains("bedwars: bedwars"), "Velocity bundled config must contain bedwars alias");
        assertTrue(src.contains("hg: hg"), "Velocity bundled config must contain hg alias");
        assertFalse(src.contains("campominado: campominado"),
                "campominado alias must stay out: BigBangMinefield owns /campominado on its backend");
    }
}
