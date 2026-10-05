package com.campfiremc.mc.backend.protocol;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MinecraftProtocolTest {
    @Test
    void encodesCommandWithNestedProtobufFields() {
        assertArrayEquals(bytes(0x0a, 1, 'a', 0x12, 9, 0x0a, 4, 'j', 'o', 'i', 'n',
                        0x12, 1, 'a'),
                MinecraftProtocol.command("a", "join", "a"));
        assertArrayEquals(bytes(0x0a, 0, 0x12, 4, 0x0a, 0, 0x12, 0),
                MinecraftProtocol.command("", "", ""));
    }

    @Test
    void encodesMessageInUtf8IncludingEmptyString() {
        assertArrayEquals(bytes(0x0a, 1, 'a', 0x1a, 6,
                        0xe4, 0xbd, 0xa0, 0xe5, 0xa5, 0xbd),
                MinecraftProtocol.message("a", "你好"));
        assertArrayEquals(bytes(0x0a, 0, 0x1a, 0), MinecraftProtocol.message("", ""));
    }

    @Test
    void decodesChatAndDistinguishesExplicitFalseFromMissingCommandField() {
        assertEquals(new MinecraftProtocol.Chat("服务端", ""),
                MinecraftProtocol.decodeServerFrame(bytes(0x0a, 9,
                        0xe6, 0x9c, 0x8d, 0xe5, 0x8a, 0xa1, 0xe7, 0xab, 0xaf,
                        0x12, 0)));
        assertEquals(new MinecraftProtocol.CommandResult("", "leave", false),
                MinecraftProtocol.decodeServerFrame(bytes(0x0a, 0, 0x12, 5,
                        'l', 'e', 'a', 'v', 'e', 0x18, 0)));
        assertEquals(new MinecraftProtocol.CommandResult("u", "join", true),
                MinecraftProtocol.decodeServerFrame(bytes(0x0a, 1, 'u', 0x12, 4,
                        'j', 'o', 'i', 'n', 0x18, 1)));
    }

    @Test
    void skipsUnknownFieldsOfEachSupportedWireType() {
        assertEquals(new MinecraftProtocol.Chat("a", "b"), MinecraftProtocol.decodeServerFrame(bytes(
                0x20, 0x96, 1,                    // unknown varint
                0x38, 0xff, 0xff, 0xff, 0xff, 0x0f, // maximum 32-bit varint
                0x29, 1, 2, 3, 4, 5, 6, 7, 8,    // unknown 64-bit
                0x32, 2, 0, 1,                   // unknown length-delimited
                0x3d, 1, 2, 3, 4,                // unknown 32-bit
                0x0a, 1, 'a', 0x12, 1, 'b')));
    }

    @Test
    void rejectsMalformedOrTruncatedFrames() {
        byte[][] invalid = {
                bytes(0x0a, 2, 'a'), bytes(0x0a, 1, 'a', 0x12, 1, 'b', 0x18),
                bytes(0x0a, 1, 'a', 0x12, 1, 'b', 0x10, 1), // wrong field wire type
                bytes(0x0a, 1, 0xff, 0x12, 1, 'b'),        // invalid UTF-8
                bytes(0x0a, 1, 'a', 0x12, 1, 'b', 0x29, 1), // truncated fixed64
                bytes(0x0a, 1, 'a', 0x12, 1, 'b', 0x32, 2, 1),
                bytes(0x0a, 1, 'a', 0x12, 1, 'b', 0x3d, 1), // truncated fixed32
                bytes(0x0a, 1, 'a', 0x12, 1, 'b', 0x20, 0x80),
                bytes(0x0a, 1, 'a', 0x12, 1, 'b', 0x20, 0x80, 0x80, 0x80, 0x80, 0x10),
                bytes(0x0a, 1, 'a', 0x12, 1, 'b', 0x20, 0x80, 0x80, 0x80, 0x80, 0x80, 0),
                bytes(0x00, 0)
        };
        for (byte[] frame : invalid)
            assertThrows(IllegalArgumentException.class, () -> MinecraftProtocol.decodeServerFrame(frame));
    }

    @Test
    void defaultsMissingStringsToEmptyAndPreservesExplicitFalse() {
        assertEquals(new MinecraftProtocol.Chat("", ""), MinecraftProtocol.decodeServerFrame(bytes()));
        assertEquals(new MinecraftProtocol.Chat("a", ""), MinecraftProtocol.decodeServerFrame(bytes(0x0a, 1, 'a')));
        assertEquals(new MinecraftProtocol.CommandResult("", "", false),
                MinecraftProtocol.decodeServerFrame(bytes(0x18, 0)));
    }

    @Test
    void encodesBindAsCommandRatherThanChat() {
        assertArrayEquals(bytes(0x0a, 1, 'u', 0x12, 14,
                        0x0a, 4, 'b', 'i', 'n', 'd', 0x12, 6, '1', '2', '3', '4', '5', '6'),
                MinecraftProtocol.command("u", "bind", "123456"));
    }

    private static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int i = 0; i < values.length; i++) result[i] = (byte) values[i];
        return result;
    }
}
