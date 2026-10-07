package com.campfiremc.mc.config;

import com.google.gson.annotations.SerializedName;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.UUID;

/** Operator-managed settings; a newly created configuration is intentionally disabled. */
public record CampfireConfig(boolean enabled, String baseUrl, String accountUuid, String consoleUuid,
                             @SerializedName("private-key-base64") String privateKeyBase64,
                             int connectTimeoutSeconds, int requestTimeoutSeconds, int handshakeTimeoutSeconds,
                             int initialReconnectSeconds, int maxReconnectSeconds, int maxMessageBytes) {
    public static CampfireConfig defaults() {
        return new CampfireConfig(false, "", "", "", "", 10, 15, 15, 5, 60, 1_048_576);
    }

    @Override
    public String toString() {
        return "CampfireConfig[enabled=" + enabled + ", private-key-base64=<redacted>]";
    }

    public CampfireConfig validate() {
        if (baseUrl == null || accountUuid == null || consoleUuid == null || privateKeyBase64 == null) {
            throw new IllegalArgumentException("baseUrl, accountUuid, consoleUuid and private-key-base64 must be strings");
        }
        if (enabled && (baseUrl.isBlank() || accountUuid.isBlank() || privateKeyBase64.isBlank())) {
            throw new IllegalArgumentException("enabled requires baseUrl, accountUuid and private-key-base64");
        }
        if (!baseUrl.isBlank()) {
            try {
                URI uri = new URI(baseUrl);
                if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                        || uri.getHost() == null || uri.getUserInfo() != null
                        || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                    throw new IllegalArgumentException("baseUrl must be an http(s) URL without credentials, query or fragment");
                }
            } catch (URISyntaxException e) {
                throw new IllegalArgumentException("baseUrl is not a valid URL", e);
            }
        }
        if (!consoleUuid.isBlank()) {
            try {
                UUID parsed = UUID.fromString(consoleUuid);
                if (!parsed.toString().equals(consoleUuid)) {
                    throw new IllegalArgumentException("consoleUuid must be a canonical UUID");
                }
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("consoleUuid must be a canonical UUID", e);
            }
        }
        if (connectTimeoutSeconds <= 0 || requestTimeoutSeconds <= 0 || handshakeTimeoutSeconds <= 0
                || initialReconnectSeconds <= 0 || maxReconnectSeconds < initialReconnectSeconds
                || maxMessageBytes <= 0) {
            throw new IllegalArgumentException("timeouts, reconnect delays and maxMessageBytes must be positive; maxReconnectSeconds must not be less than initialReconnectSeconds");
        }
        return this;
    }
}
