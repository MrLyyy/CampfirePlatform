package com.campfiremc.mc.protocol;

import static org.junit.jupiter.api.Assertions.*;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class MinecraftProtocolTest {
    private static byte[] hex(String bytes) { return HexFormat.of().parseHex(bytes.replace(" ", "")); }

    @Test void outgoingGoldenBytes() {
        assertArrayEquals(hex("0A 01 61 12 09 0A 04 6A 6F 69 6E 12 01 61"), MinecraftProtocol.command("a", "join", "a"));
        assertArrayEquals(hex("0A 01 75 12 0E 0A 04 62 69 6E 64 12 06 31 32 33 34 35 36"), MinecraftProtocol.command("u", "bind", "123456"));
        assertArrayEquals(hex("0A 00 12 04 0A 00 12 00"), MinecraftProtocol.command("", "", ""));
        assertArrayEquals(hex("0A 01 61 1A 06 E4 BD A0 E5 A5 BD"), MinecraftProtocol.message("a", "你好"));
        assertArrayEquals(hex("0A 00 1A 00"), MinecraftProtocol.message("", ""));
        assertEquals(133, MinecraftProtocol.message("", "a".repeat(128)).length);
        assertArrayEquals(hex("1A 80 01"), java.util.Arrays.copyOfRange(MinecraftProtocol.message("", "a".repeat(128)), 2, 5));
    }

    @Test void incomingGoldenBytesAndPresence() {
        assertEquals(new MinecraftProtocol.Chat("server", "hi"), MinecraftProtocol.decode(hex("0A 06 73 65 72 76 65 72 12 02 68 69")));
        assertEquals(new MinecraftProtocol.Chat("服务端", ""), MinecraftProtocol.decode(hex("0A 09 E6 9C 8D E5 8A A1 E7 AB AF 12 00")));
        assertEquals(new MinecraftProtocol.CommandResult("u", "join", true), MinecraftProtocol.decode(hex("0A 01 75 12 04 6A 6F 69 6E 18 01")));
        assertEquals(new MinecraftProtocol.CommandResult("", "leave", false), MinecraftProtocol.decode(hex("0A 00 12 05 6C 65 61 76 65 18 00")));
        assertEquals(new MinecraftProtocol.Chat("", ""), MinecraftProtocol.decode(new byte[0]));
        assertEquals(new MinecraftProtocol.CommandResult("b", "x", false), MinecraftProtocol.decode(hex("18 01 0A 01 61 18 00 0A 01 62 12 01 78")));
        assertEquals(new MinecraftProtocol.Chat("", "hi"), MinecraftProtocol.decode(hex("22 01 00 29 00 00 00 00 00 00 00 00 35 00 00 00 00 12 02 68 69")));
    }

    @Test void malformedPacketsFail() {
        String[] invalid = {"00", "08 00", "10 00", "1A 00", "18", "0A 02 61", "0A 01 FF", "80", "80 80 80 80 10", "80 80 80 80 80 00", "20 80 80 80 80 10", "23", "24", "29 00", "35 00", "22 03 00", "0A FF FF FF FF 0F"};
        for (String packet : invalid) assertThrows(IllegalArgumentException.class,
                () -> MinecraftProtocol.decode(hex(packet)), packet);
    }
}
