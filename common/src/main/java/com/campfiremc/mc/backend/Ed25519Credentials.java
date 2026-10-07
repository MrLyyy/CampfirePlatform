package com.campfiremc.mc.backend;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.EdECPrivateKeySpec;
import java.security.spec.NamedParameterSpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;

/** Signs platform verification challenges with a 32-byte raw Ed25519 seed. */
public final class Ed25519Credentials {
    private final PrivateKey key;

    public Ed25519Credentials(byte[] seed) {
        Objects.requireNonNull(seed, "seed");
        if (seed.length != 32) throw new IllegalArgumentException("Ed25519 seed must contain 32 bytes");
        byte[] copy = seed.clone();
        try {
            key = KeyFactory.getInstance("Ed25519")
                    .generatePrivate(new EdECPrivateKeySpec(NamedParameterSpec.ED25519, copy));
        } catch (Exception e) {
            throw new IllegalStateException("Ed25519 unavailable", e);
        } finally {
            Arrays.fill(copy, (byte) 0);
        }
    }

    public String sign(String account, long timestamp) {
        try {
            Signature signature = Signature.getInstance("Ed25519");
            signature.initSign(key);
            signature.update((Objects.requireNonNull(account, "account").trim() + ":" + timestamp)
                    .getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (Exception e) {
            throw new IllegalStateException("Ed25519 signing failed", e);
        }
    }
}
