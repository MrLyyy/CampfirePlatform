package com.campfiremc.mc.protocol;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Wire format for platform Minecraft commands and server notifications. */
public final class MinecraftProtocol {
    private MinecraftProtocol() { }

    public sealed interface ServerMessage permits Chat, CommandResult { }
    public record Chat(String source, String message) implements ServerMessage { }
    public record CommandResult(String uuid, String action, boolean accepted) implements ServerMessage { }

    public static byte[] command(String uuid, String action, String pattern) {
        ByteArrayOutputStream inner = new ByteArrayOutputStream();
        writeString(inner, 1, action);
        writeString(inner, 2, pattern);
        ByteArrayOutputStream outer = new ByteArrayOutputStream();
        writeString(outer, 1, uuid);
        writeVarint(outer, 18);
        writeVarint(outer, inner.size());
        outer.writeBytes(inner.toByteArray());
        return outer.toByteArray();
    }

    public static byte[] message(String uuid, String message) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeString(out, 1, uuid);
        writeString(out, 3, message);
        return out.toByteArray();
    }

    public static ServerMessage decode(byte[] bytes) {
        Reader reader = new Reader(Objects.requireNonNull(bytes, "bytes"));
        String first = "";
        String second = "";
        boolean hasResult = false;
        boolean accepted = false;
        while (reader.hasRemaining()) {
            long tag = reader.varint();
            long field = tag >>> 3;
            int wire = (int) (tag & 7);
            if (field == 0) throw malformed();
            if (field == 1 || field == 2) {
                if (wire != 2) throw malformed();
                if (field == 1) first = reader.string();
                else second = reader.string();
            } else if (field == 3) {
                if (wire != 0) throw malformed();
                accepted = reader.varint() != 0;
                hasResult = true;
            } else {
                reader.skip(wire);
            }
        }
        return hasResult ? new CommandResult(first, second, accepted) : new Chat(first, second);
    }

    private static void writeString(ByteArrayOutputStream out, int field, String value) {
        byte[] utf8 = Objects.requireNonNull(value, "value").getBytes(StandardCharsets.UTF_8);
        writeVarint(out, field << 3 | 2);
        writeVarint(out, utf8.length);
        out.writeBytes(utf8);
    }

    private static void writeVarint(ByteArrayOutputStream out, int value) {
        while ((value & ~127) != 0) {
            out.write((value & 127) | 128);
            value >>>= 7;
        }
        out.write(value);
    }

    private static IllegalArgumentException malformed() {
        return new IllegalArgumentException("invalid backend protobuf message");
    }

    private static final class Reader {
        private final byte[] data;
        private int pos;

        private Reader(byte[] data) { this.data = data; }
        private boolean hasRemaining() { return pos < data.length; }

        private long varint() {
            long result = 0;
            for (int i = 0; i < 5; i++) {
                if (!hasRemaining()) throw malformed();
                int b = data[pos++] & 255;
                if (i == 4 && (b & 0xf0) != 0) throw malformed();
                result |= (long) (b & 127) << (i * 7);
                if ((b & 128) == 0) return result;
            }
            throw malformed();
        }

        private int length() {
            long n = varint();
            if (n > data.length - pos) throw malformed();
            return (int) n;
        }

        private String string() {
            int n = length();
            try {
                String value = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(data, pos, n)).toString();
                pos += n;
                return value;
            } catch (CharacterCodingException e) {
                throw malformed();
            }
        }

        private void skip(int wire) {
            switch (wire) {
                case 0 -> varint();
                case 1 -> advance(8);
                case 2 -> advance(length());
                case 5 -> advance(4);
                default -> throw malformed();
            }
        }

        private void advance(int n) {
            if (n > data.length - pos) throw malformed();
            pos += n;
        }
    }
}
