package com.campfiremc.mc.config;

import com.campfiremc.mc.backend.auth.Ed25519Credentials;
import java.security.KeyPair;
import java.util.Base64;
import org.bukkit.plugin.java.JavaPlugin;

public final class PrivateKeyStore {
    private PrivateKeyStore() {}

    public static byte[] loadOrCreate(JavaPlugin plugin) throws Exception {
        String configured = plugin.getConfig().getString("backend.private-key-base64", "").trim();
        if (!configured.isEmpty()) {
            byte[] seed = Base64.getDecoder().decode(configured);
            if (seed.length != 32) throw new IllegalArgumentException("backend.private-key-base64 must be 32 raw bytes in Base64");
            return seed;
        }
        KeyPair pair = Ed25519Credentials.generateKeyPair();
        byte[] seed = Ed25519Credentials.privateSeed(pair);
        plugin.getConfig().set("backend.private-key-base64", Base64.getEncoder().encodeToString(seed));
        plugin.saveConfig();
        plugin.getLogger().warning("Generated a private key and saved it to config.yml. Register this public key in the backend platform record: "
                + Base64.getEncoder().encodeToString(Ed25519Credentials.publicKey(pair)));
        return seed;
    }
}
