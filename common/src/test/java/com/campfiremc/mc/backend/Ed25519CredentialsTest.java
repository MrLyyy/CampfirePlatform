package com.campfiremc.mc.backend;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Base64;
import java.util.HexFormat;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class Ed25519CredentialsTest {
    @Test void signsTrimmedAccountAndEpochSeconds() throws Exception {
        byte[] seed = HexFormat.of().parseHex("9d61b19deffd5a60ba844af492ec2cc4" + "4449c5697b326919703bac031cae7f60");
        Ed25519Credentials credential = new Ed25519Credentials(seed);
        String signature = credential.sign(" account ", 1700000000);
        assertEquals(signature, credential.sign("account", 1700000000));
        assertEquals(64, Base64.getDecoder().decode(signature).length);
        byte[] spki = HexFormat.of().parseHex("302a300506032b6570032100d75a980182b10ab7d54bfed3c964073a" + "0ee172f3daa62325af021a68f707511a");
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(spki)));
        verifier.update("account:1700000000".getBytes(StandardCharsets.UTF_8));
        assertTrue(verifier.verify(Base64.getDecoder().decode(signature)));
        assertNotEquals(signature, credential.sign("account", 1700000001));
        assertThrows(IllegalArgumentException.class, () -> new Ed25519Credentials(new byte[64]));
    }
}
