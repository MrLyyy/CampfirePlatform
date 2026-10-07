package com.campfiremc.mc.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.Set;

/** Reads operator-supplied credentials; public keys are managed by the backend. */
public final class ConfigStore {
    private static final String DIRECTORY = "campfireplatform";
    private static final String CONFIG_FILE = "config.json";
    private static final Set<String> FIELDS = Set.of("enabled", "baseUrl", "accountUuid", "consoleUuid",
            "connectTimeoutSeconds", "requestTimeoutSeconds", "handshakeTimeoutSeconds",
            "initialReconnectSeconds", "maxReconnectSeconds", "maxMessageBytes");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private ConfigStore() {}

    public record Loaded(CampfireConfig config, byte[] seed) {
        public Loaded {
            seed = seed.clone();
        }
        @Override public byte[] seed() { return seed.clone(); }
    }

    public static Loaded load(Path loaderConfigDir) throws IOException, GeneralSecurityException {
        Path directory = loaderConfigDir.resolve(DIRECTORY);
        Files.createDirectories(directory);
        Path configFile = directory.resolve(CONFIG_FILE);
        if (!Files.exists(configFile)) {
            try {
                Files.writeString(configFile, GSON.toJson(CampfireConfig.defaults()) + System.lineSeparator(),
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            } catch (FileAlreadyExistsException ignored) {
                // Read a concurrently created configuration rather than replacing it.
            }
        }
        CampfireConfig config = readConfig(configFile);
        String encoded = config.privateKeyBase64();
        if (encoded.isBlank()) return new Loaded(config, new byte[0]);
        byte[] seed;
        try {
            seed = Base64.getDecoder().decode(encoded.trim());
        } catch (IllegalArgumentException ignored) {
            throw new GeneralSecurityException("private-key-base64 must be standard Base64 (left unchanged)");
        }
        if (seed.length != 32) {
            throw new GeneralSecurityException("private-key-base64 must encode exactly 32 Ed25519 seed bytes (left unchanged)");
        }
        return new Loaded(config, seed);
    }

    private static CampfireConfig readConfig(Path file) throws IOException {
        try {
            JsonReader reader = new JsonReader(new StringReader(Files.readString(file, StandardCharsets.UTF_8)));
            reader.setLenient(false);
            JsonElement parsed = JsonParser.parseReader(reader);
            if (reader.peek() != JsonToken.END_DOCUMENT || !parsed.isJsonObject()) {
                throw new IllegalArgumentException("expected a single configuration object");
            }
            JsonObject json = parsed.getAsJsonObject();
            if (!json.keySet().containsAll(FIELDS)
                    || json.keySet().stream().anyMatch(key -> !FIELDS.contains(key) && !key.equals("private-key-base64"))) {
                throw new IllegalArgumentException("unexpected or missing configuration fields");
            }
            // Older configs remain readable while disabled; enabling requires an explicit configured key.
            String key = json.has("private-key-base64") ? string(json, "private-key-base64") : "";
            return new CampfireConfig(bool(json, "enabled"), string(json, "baseUrl"),
                    string(json, "accountUuid"), string(json, "consoleUuid"), key,
                    integer(json, "connectTimeoutSeconds"), integer(json, "requestTimeoutSeconds"),
                    integer(json, "handshakeTimeoutSeconds"), integer(json, "initialReconnectSeconds"),
                    integer(json, "maxReconnectSeconds"), integer(json, "maxMessageBytes")).validate();
        } catch (IllegalArgumentException | IllegalStateException | JsonParseException e) {
            // Parsing exceptions can contain input fragments, including the configured private key.
            throw new IOException("Invalid configuration: check fields and private-key-base64 (left unchanged)");
        }
    }

    private static boolean bool(JsonObject json, String field) {
        JsonElement value = json.get(field);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException(field + " must be a boolean");
        }
        return value.getAsBoolean();
    }

    private static String string(JsonObject json, String field) {
        JsonElement value = json.get(field);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(field + " must be a string");
        }
        return value.getAsString();
    }

    private static int integer(JsonObject json, String field) {
        JsonElement value = json.get(field);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()
                || !value.getAsString().matches("[0-9]+")) {
            throw new IllegalArgumentException(field + " must be a nonnegative integer");
        }
        try {
            return Integer.parseInt(value.getAsString());
        } catch (NumberFormatException ignored) {
            throw new IllegalArgumentException(field + " is out of range");
        }
    }
}
