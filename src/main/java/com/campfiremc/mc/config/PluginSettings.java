package com.campfiremc.mc.config;

import java.time.Duration;
import java.util.Objects;

public record PluginSettings(Backend backend, Commands commands) {
    public PluginSettings {
        Objects.requireNonNull(backend, "backend");
        Objects.requireNonNull(commands, "commands");
    }

    public record Backend(
            String baseUrl,
            String accountUuid,
            boolean connectOnStart,
            Duration timeout,
            int maxFrameBytes,
            int initialReconnectSeconds,
            int maxReconnectSeconds
    ) {
        public Backend {
            baseUrl = Objects.requireNonNull(baseUrl, "baseUrl");
            accountUuid = Objects.requireNonNull(accountUuid, "accountUuid").trim();
            timeout = Duration.ofSeconds(Math.max(1, Objects.requireNonNull(timeout, "timeout").getSeconds()));
            maxFrameBytes = Math.max(1024, maxFrameBytes);
            initialReconnectSeconds = Math.max(1, initialReconnectSeconds);
            maxReconnectSeconds = Math.max(initialReconnectSeconds, maxReconnectSeconds);
        }
    }

    public record Commands(String permission, String consolePlayerUuid) {
        public Commands {
            Objects.requireNonNull(permission, "permission");
            Objects.requireNonNull(consolePlayerUuid, "consolePlayerUuid");
        }
    }
}
