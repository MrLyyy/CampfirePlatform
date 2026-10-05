package com.campfiremc.mc.backend.protocol;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

public final class MinecraftProtocol {
    private MinecraftProtocol() {}

    public sealed interface ServerFrame permits Chat, CommandResult {}

    public record Chat(String source, String message) implements ServerFrame {}

    public record CommandResult(String uuid, String action, boolean accepted) implements ServerFrame {}

    public static byte[] command(String playerUuid, String action, String patten) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeString(output, 1, playerUuid);
        ByteArrayOutputStream command = new ByteArrayOutputStream();
        writeString(command, 1, action);
        writeString(command, 2, patten);
        writeBytes(output, 2, command.toByteArray());
        return output.toByteArray();
    }

    public static byte[] message(String playerUuid, String message) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeString(output, 1, playerUuid);
        writeString(output, 3, message);
        return output.toByteArray();
    }

    /**
     * Tag 3 identifies an explicit command result. Without it, Chat is a compatibility
     * fallback: proto3 may omit accept=false, so the current wire format is ambiguous.
     */
    public static ServerFrame decodeServerFrame(byte[] data) {
        Reader reader = new Reader(data);
        String first = "";
        String second = "";
        Boolean accepted = null;
        while (reader.hasRemaining()) {
            int key = reader.readVarint();
            int field = key >>> 3;
            int wireType = key & 7;
            if (field == 0) throw new IllegalArgumentException("invalid protobuf field number");
            switch (field) {
                case 1, 2 -> {
                    requireWireType(wireType, 2);
                    String value = reader.readString();
                    if (field == 1) first = value; else second = value;
                }
                case 3 -> {
                    requireWireType(wireType, 0);
                    accepted = reader.readVarint() != 0;
                }
                default -> reader.skip(wireType);
            }
        }
        return accepted == null ? new Chat(first, second) : new CommandResult(first, second, accepted);
    }

    private static void writeString(ByteArrayOutputStream output, int field, String value) {
        writeBytes(output, field, value.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeBytes(ByteArrayOutputStream output, int field, byte[] value) {
        writeVarint(output, (field << 3) | 2);
        writeVarint(output, value.length);
        output.writeBytes(value);
    }

    private static void writeVarint(ByteArrayOutputStream output, int value) {
        while ((value & ~0x7f) != 0) {
            output.write((value & 0x7f) | 0x80);
            value >>>= 7;
        }
        output.write(value);
    }

    private static void requireWireType(int actual, int expected) {
        if (actual != expected) throw new IllegalArgumentException("unexpected protobuf wire type");
    }

    private static final class Reader {
        private final byte[] data;
        private int offset;

        private Reader(byte[] data) { this.data = data; }
        private boolean hasRemaining() { return offset < data.length; }

        private int readVarint() {
            int result = 0;
            for (int index = 0; index < 5; index++) {
                if (!hasRemaining()) throw new IllegalArgumentException("truncated protobuf varint");
                int value = data[offset++] & 0xff;
                if (index == 4 && (value & 0xf0) != 0)
                    throw new IllegalArgumentException("protobuf varint exceeds 32 bits");
                result |= (value & 0x7f) << (index * 7);
                if ((value & 0x80) == 0) return result;
            }
            throw new IllegalArgumentException("protobuf varint is too long");
        }

        private String readString() {
            int length = readVarint();
            if (length < 0 || length > data.length - offset)
                throw new IllegalArgumentException("truncated protobuf string");
            try {
                String value = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(data, offset, length)).toString();
                offset += length;
                return value;
            } catch (CharacterCodingException exception) {
                throw new IllegalArgumentException("invalid protobuf UTF-8 string", exception);
            }
        }

        private void skip(int wireType) {
            switch (wireType) {
                case 0 -> readVarint();
                case 1 -> skipBytes(8);
                case 2 -> skipBytes(readVarint());
                case 5 -> skipBytes(4);
                default -> throw new IllegalArgumentException("unsupported protobuf wire type");
            }
        }

        private void skipBytes(int length) {
            if (length < 0 || length > data.length - offset)
                throw new IllegalArgumentException("truncated protobuf field");
            offset += length;
        }
    }
}
