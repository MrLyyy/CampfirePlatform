package com.campfiremc.mc.backend.auth;

import java.io.ByteArrayOutputStream;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BackendAuthenticatorTest {
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochSecond(1_700_000_000), ZoneOffset.UTC);

    @Test
    void signsTrimmedAccountWithFixedTimestampAndRawPublicKeyRemains32Bytes() throws Exception {
        KeyPair pair = Ed25519Credentials.generateKeyPair();
        byte[] seed = Ed25519Credentials.privateSeed(pair);
        assertEquals(32, seed.length);
        byte[] expectedPublic = java.util.Arrays.copyOfRange(pair.getPublic().getEncoded(),
                pair.getPublic().getEncoded().length - 32, pair.getPublic().getEncoded().length);
        assertArrayEquals(expectedPublic, Ed25519Credentials.publicKey(pair));
        Ed25519Credentials credentials = new Ed25519Credentials(seed);
        byte[] actual = Base64.getDecoder().decode(credentials.sign(" account ", CLOCK.instant().getEpochSecond()));
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(pair.getPublic());
        verifier.update("account:1700000000".getBytes(StandardCharsets.UTF_8));
        assertTrue(verifier.verify(actual));
        assertArrayEquals(actual, Base64.getDecoder().decode(credentials.sign(" account ", 1_700_000_000)));
        seed[0] ^= 0x7f;
        assertArrayEquals(actual, Base64.getDecoder().decode(credentials.sign(" account ", 1_700_000_000)));
    }

    @Test
    void validatesSeedLengthAndClonesConstructorInput() throws Exception {
        assertThrows(NullPointerException.class, () -> new Ed25519Credentials(null));
        assertThrows(IllegalArgumentException.class, () -> new Ed25519Credentials(new byte[31]));
        assertThrows(IllegalArgumentException.class, () -> new Ed25519Credentials(new byte[33]));
        byte[] seed = Ed25519Credentials.privateSeed(Ed25519Credentials.generateKeyPair());
        Ed25519Credentials credentials = new Ed25519Credentials(seed);
        String before = credentials.sign("u", 42);
        seed[0] ^= 1;
        assertEquals(before, credentials.sign("u", 42));
    }

    @Test
    void requestHasExpectedUriHeadersBodySignatureAndTimeout() throws Exception {
        KeyPair pair = Ed25519Credentials.generateKeyPair();
        BackendAuthenticator auth = new BackendAuthenticator(new StubHttpClient(200, "{\"data\":{\"key\":12}}"),
                "https://example.test/", " x\"y ", new Ed25519Credentials(Ed25519Credentials.privateSeed(pair)),
                Duration.ofSeconds(5), CLOCK);
        HttpRequest request = auth.request();
        assertEquals(URI.create("https://example.test/api/platform/verify"), request.uri());
        assertEquals("POST", request.method());
        assertEquals(Optional.of(Duration.ofSeconds(5)), request.timeout());
        assertEquals(Optional.of("application/json"), request.headers().firstValue("Content-Type"));
        String body = body(request);
        String prefix = "{\"account_uuid\":\"x\\\"y\",\"timestamp\":1700000000,\"signature\":\"";
        assertTrue(body.startsWith(prefix), body);
        assertTrue(body.endsWith("\"}"), body);
        String signature = body.substring(prefix.length(), body.length() - 2);
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(pair.getPublic());
        verifier.update("x\"y:1700000000".getBytes(StandardCharsets.UTF_8));
        assertTrue(verifier.verify(Base64.getDecoder().decode(signature)));
    }

    @Test
    void verifyHonorsStatusAndOriginalNumericKeyRule() throws Exception {
        KeyPair pair = Ed25519Credentials.generateKeyPair();
        Ed25519Credentials credentials = new Ed25519Credentials(Ed25519Credentials.privateSeed(pair));
        assertEquals(12, auth(new StubHttpClient(204, "{\"data\":{\"key\":12}}"), credentials).verify().join().intValue());
        assertEquals(7, auth(new StubHttpClient(200, "{\"key\":7}"), credentials).verify().join().intValue());
        assertInstanceOf(IllegalStateException.class, assertThrows(java.util.concurrent.CompletionException.class,
                () -> auth(new StubHttpClient(401, "{\"key\":12}"), credentials).verify().join()).getCause());
        assertInstanceOf(IllegalStateException.class, assertThrows(java.util.concurrent.CompletionException.class,
                () -> auth(new StubHttpClient(200, "{\"data\":{}}"), credentials).verify().join()).getCause());
    }

    @Test
    void rejectsInvalidKeyButRetainsLegacyRegexRecognition() {
        assertNull(BackendAuthenticator.parseKey("{\"data\":{\"key\":-1}}"));
        assertNull(BackendAuthenticator.parseKey("{\"key\":\"2\"}"));
        assertNull(BackendAuthenticator.parseKey("{\"key\":2147483648}"));
        assertNull(BackendAuthenticator.parseKey("{}"));
        assertEquals(0, BackendAuthenticator.parseKey("{\"key\":0}").intValue());
        assertEquals(3, BackendAuthenticator.parseKey("{\"not_data\":{\"key\":3}}").intValue());
    }

    private static BackendAuthenticator auth(HttpClient client, Ed25519Credentials credentials) {
        return new BackendAuthenticator(client, "https://example.test", "account", credentials,
                Duration.ofSeconds(5), CLOCK);
    }

    private static String body(HttpRequest request) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            @Override public void onNext(ByteBuffer item) {
                byte[] bytes = new byte[item.remaining()];
                item.get(bytes);
                out.writeBytes(bytes);
            }
            @Override public void onError(Throwable throwable) { throw new AssertionError(throwable); }
            @Override public void onComplete() {}
        });
        return out.toString(StandardCharsets.UTF_8);
    }

    private static final class StubHttpClient extends HttpClient {
        private final int status;
        private final String body;

        private StubHttpClient(int status, String body) { this.status = status; this.body = body; }
        @Override public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }
        @Override public Optional<Duration> connectTimeout() { return Optional.empty(); }
        @Override public Redirect followRedirects() { return Redirect.NEVER; }
        @Override public Optional<ProxySelector> proxy() { return Optional.empty(); }
        @Override public SSLContext sslContext() { return null; }
        @Override public SSLParameters sslParameters() { return null; }
        @Override public Optional<Authenticator> authenticator() { return Optional.empty(); }
        @Override public Version version() { return Version.HTTP_1_1; }
        @Override public Optional<Executor> executor() { return Optional.empty(); }
        @Override public WebSocket.Builder newWebSocketBuilder() { throw new UnsupportedOperationException(); }
        @Override public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            throw new UnsupportedOperationException();
        }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            @SuppressWarnings("unchecked") HttpResponse<T> result = (HttpResponse<T>) new StubResponse(request, status, body);
            return CompletableFuture.completedFuture(result);
        }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request, HttpResponse.BodyHandler<T> handler, HttpResponse.PushPromiseHandler<T> pushHandler) {
            return sendAsync(request, handler);
        }
    }

    private record StubResponse(HttpRequest request, int statusCode, String body) implements HttpResponse<String> {
        @Override public Optional<HttpResponse<String>> previousResponse() { return Optional.empty(); }
        @Override public HttpHeaders headers() { return HttpHeaders.of(java.util.Map.of(), (name, value) -> true); }
        @Override public URI uri() { return request.uri(); }
        @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
        @Override public Optional<javax.net.ssl.SSLSession> sslSession() { return Optional.empty(); }
    }
}
