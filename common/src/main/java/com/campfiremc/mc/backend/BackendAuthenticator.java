package com.campfiremc.mc.backend;

import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.StringReader;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** Performs the asynchronous HTTP verification step without exposing response bodies. */
public final class BackendAuthenticator {
    private final HttpClient http;
    private final URI baseUrl;
    private final Duration requestTimeout;

    public BackendAuthenticator(HttpClient http, URI baseUrl, Duration requestTimeout) {
        this.http = Objects.requireNonNull(http, "http");
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl");
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
    }

    public CompletableFuture<Integer> verify(String account, Ed25519Credentials credentials) {
        return verify(account, credentials, Instant.now().getEpochSecond());
    }

    /** Explicit timestamp is useful when validating signatures against a local HTTP server. */
    public CompletableFuture<Integer> verify(String account, Ed25519Credentials credentials, long timestamp) {
        String trimmed = Objects.requireNonNull(account, "account").trim();
        String url = baseUrl.toString().replaceFirst("/$", "") + "/api/platform/verify";
        JsonObject json = new JsonObject();
        json.addProperty("account_uuid", trimmed);
        json.addProperty("timestamp", timestamp);
        json.addProperty("signature", Objects.requireNonNull(credentials, "credentials").sign(trimmed, timestamp));
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.toString())).build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        throw new IllegalArgumentException("verification rejected with HTTP " + response.statusCode());
                    }
                    return parseKey(response.body());
                });
    }

    static int parseKey(String body) {
        try {
            JsonReader reader = new JsonReader(new StringReader(body));
            reader.setLenient(false);
            reader.beginObject();
            Integer key = null;
            while (reader.hasNext()) {
                if (reader.nextName().equals("data")) {
                    if (reader.peek() != JsonToken.BEGIN_OBJECT) throw new IllegalArgumentException();
                    reader.beginObject();
                    while (reader.hasNext()) {
                        if (reader.nextName().equals("key")) {
                            if (reader.peek() != JsonToken.NUMBER) throw new IllegalArgumentException();
                            String number = reader.nextString();
                            if (!number.matches("(?:0|[1-9][0-9]*)")) throw new IllegalArgumentException();
                            key = Integer.parseInt(number);
                        } else reader.skipValue();
                    }
                    reader.endObject();
                } else reader.skipValue();
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT || key == null) throw new IllegalArgumentException();
            return key;
        } catch (RuntimeException | IOException e) {
            throw new IllegalArgumentException("verification response missing valid data.key");
        }
    }
}
