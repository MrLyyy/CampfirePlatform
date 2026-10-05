package com.campfiremc.mc.backend.auth;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.EdECPrivateKey;
import java.security.spec.EdECPrivateKeySpec;
import java.security.spec.NamedParameterSpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;

public final class Ed25519Credentials {
    private final byte[] privateSeed;

    public Ed25519Credentials(byte[] privateSeed) {
        Objects.requireNonNull(privateSeed, "privateSeed");
        if (privateSeed.length != 32) throw new IllegalArgumentException("Ed25519 private seed must be 32 bytes");
        this.privateSeed = privateSeed.clone();
    }

    public String sign(String accountUuid, long timestamp) throws GeneralSecurityException {
        String signedText = Objects.requireNonNull(accountUuid, "accountUuid").trim() + ":" + timestamp;
        PrivateKey privateKey = KeyFactory.getInstance("Ed25519").generatePrivate(
                new EdECPrivateKeySpec(NamedParameterSpec.ED25519, privateSeed));
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(privateKey);
        signer.update(signedText.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(signer.sign());
    }

    public static KeyPair generateKeyPair() throws GeneralSecurityException {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    public static byte[] privateSeed(KeyPair pair) {
        Objects.requireNonNull(pair, "pair");
        try {
            byte[] seed = ((EdECPrivateKey) pair.getPrivate()).getBytes()
                    .orElseThrow(() -> new IllegalStateException("JDK did not expose Ed25519 private seed"));
            if (seed.length != 32) throw new IllegalStateException("Ed25519 private seed must be 32 bytes");
            return seed.clone();
        } catch (ClassCastException exception) {
            throw new IllegalStateException("unsupported Ed25519 private key implementation", exception);
        }
    }

    public static byte[] publicKey(KeyPair pair) {
        Objects.requireNonNull(pair, "pair");
        byte[] encoded = pair.getPublic().getEncoded();
        if (encoded.length < 32) throw new IllegalArgumentException("invalid Ed25519 public key encoding");
        return Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length);
    }
}
