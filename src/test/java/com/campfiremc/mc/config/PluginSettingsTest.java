package com.campfiremc.mc.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class PluginSettingsTest {
    @Test
    void normalizesTimeoutFrameAndReconnectBounds() {
        var backend = new PluginSettings.Backend("http://localhost:3000", " account ", true,
                Duration.ZERO, 1, 0, -1);
        assertEquals("account", backend.accountUuid());
        assertEquals(Duration.ofSeconds(1), backend.timeout());
        assertEquals(1024, backend.maxFrameBytes());
        assertEquals(1, backend.initialReconnectSeconds());
        assertEquals(1, backend.maxReconnectSeconds());
    }

    @Test
    void maximumDelayCannotBeBelowInitialDelay() {
        var backend = new PluginSettings.Backend("http://localhost:3000", "", false,
                Duration.ofSeconds(10), 1048576, 20, 5);
        assertEquals(20, backend.maxReconnectSeconds());
        assertFalse(backend.connectOnStart());
    }
}
