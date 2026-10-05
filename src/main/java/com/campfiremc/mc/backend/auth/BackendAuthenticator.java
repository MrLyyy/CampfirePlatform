package com.campfiremc.mc.backend.auth;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class BackendAuthenticator {
    private static final Pattern KEY_PATTERN = Pattern.compile("\\\"key\\\"\\s*:\\s*(\\d+)");

    private final HttpClient httpClient;
    private final String baseUrl;
    private final String accountUuid;
    private final Ed25519Credentials credentials;
    private final Duration timeout;
    private final Clock clock;

    public BackendAuthenticator(HttpClient httpClient, String baseUrl, String accountUuid,
                                Ed25519Credentials credentials, Duration timeout, Clock clock) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        String url = Objects.requireNonNull(baseUrl, "baseUrl");
        this.baseUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        this.accountUuid = Objects.requireNonNull(accountUuid, "accountUuid");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public CompletableFuture<Integer> verify() {
        final HttpRequest request;
        try {
            request = request();
        } catch (GeneralSecurityException | RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
        try {
            return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                    .thenApply(BackendAuthenticator::responseKey);
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    HttpRequest request() throws GeneralSecurityException {
        long timestamp = clock.instant().getEpochSecond();
        String signature = credentials.sign(accountUuid, timestamp);
        String body = "{\"account_uuid\":\"" + jsonEscape(accountUuid.trim())
                + "\",\"timestamp\":" + timestamp
                + ",\"signature\":\"" + signature + "\"}";
        return HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/platform/verify"))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
    }

    static int responseKey(HttpResponse<String> response) {
        if (response.statusCode() / 100 != 2)
            throw new IllegalStateException("verification rejected with HTTP " + response.statusCode());
        Integer key = parseKey(response.body());
        if (key == null) throw new IllegalStateException("verification response did not contain data.key");
        return key;
    }

    static Integer parseKey(String body) {
        Matcher matcher = KEY_PATTERN.matcher(body);
        if (!matcher.find()) return null;
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n");
    }
}
