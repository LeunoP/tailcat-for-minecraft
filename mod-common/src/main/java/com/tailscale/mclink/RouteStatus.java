package com.tailscale.mclink;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class RouteStatus {
    public static final int NOT_TAILCAT = 0;
    public static final int DIRECT = 1;
    public static final int RELAY = 2;
    public static final int UNKNOWN = 3;
    public static final int DISCONNECTED = 4;
    public static final int MAX_PAYLOAD_BYTES = 1156;
    public static final int MAX_PACKET_PLAYERS = (MAX_PAYLOAD_BYTES - 17) / 20;
    private static final int LEGACY_MAX_PLAYERS = 64;
    private static final java.util.concurrent.atomic.AtomicLong SNAPSHOT_IDS = new java.util.concurrent.atomic.AtomicLong();
    private static final int MAGIC = 0x5443;
    private static final int PROTOCOL_VERSION = 4;
    private static final String NAME_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_";

    public record PlayerRoute(String playerName, int status, int pingMillis, boolean host, boolean disconnected) {
        public PlayerRoute(String playerName, int status, int pingMillis, boolean host) { this(playerName, status, pingMillis, host, false); }
        public PlayerRoute(String playerName, int status, int pingMillis) { this(playerName, status, pingMillis, false, false); }
        public PlayerRoute(String playerName, int status) { this(playerName, status, -1, false, false); }
    }
    public record Packet(int version, long snapshotId, int index, boolean last, List<PlayerRoute> players) {}

    private RouteStatus() {}

    public static int encode(boolean tailcatConnection, String route) {
        if (!tailcatConnection) return NOT_TAILCAT;
        return switch (route == null ? "unknown" : route) {
            case "direct" -> DIRECT;
            case "relay" -> RELAY;
            default -> UNKNOWN;
        };
    }

    public static String translationKey(int status) {
        return switch (status) {
            case DIRECT -> "mclink.status_direct";
            case RELAY -> "mclink.status_relay";
            case UNKNOWN -> "mclink.status_unknown";
            case DISCONNECTED -> "mclink.status_disconnected";
            default -> "mclink.status_not_tailcat";
        };
    }

    public static String commandLabel(int status, String language) {
        return ServerMessages.text(language, translationKey(status));
    }

    public static byte[] encode(List<PlayerRoute> players) {
        if (players.size() > MAX_PACKET_PLAYERS) throw new IllegalArgumentException("Use encodePackets for multi-packet snapshots");
        return encodePackets(players).get(0);
    }

    public static List<byte[]> encodePackets(List<PlayerRoute> players) {
        List<PlayerRoute> stable = List.copyOf(players);
        long id = SNAPSHOT_IDS.incrementAndGet();
        List<byte[]> packets = new ArrayList<>();
        int start = 0, index = 0;
        do {
            int end = start + Math.min(MAX_PACKET_PLAYERS, stable.size() - start);
            packets.add(encodePacket(id, index++, end == stable.size(), stable.subList(start, end)));
            start = end;
        } while (start < stable.size());
        return List.copyOf(packets);
    }

    private static byte[] encodePacket(long snapshotId, int index, boolean last, List<PlayerRoute> players) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(MAX_PAYLOAD_BYTES);
            DataOutputStream output = new DataOutputStream(bytes);
            output.writeShort(MAGIC);
            output.writeByte(PROTOCOL_VERSION);
            output.writeLong(snapshotId);
            output.writeInt(index);
            output.writeBoolean(last);
            output.writeByte(players.size());
            for (PlayerRoute player : players) {
                validate(player);
                byte[] name = packName(player.playerName());
                output.writeByte(player.playerName().length());
                output.write(name);
                output.writeByte(player.status());
                output.writeInt(player.pingMillis());
                output.writeBoolean(player.host());
                output.writeBoolean(player.disconnected());
            }
            output.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException("Could not encode route status", impossible);
        }
    }

    public static List<PlayerRoute> decode(byte[] bytes) throws IOException {
        Packet packet = decodePacket(bytes);
        if (packet.index() != 0 || !packet.last()) throw new IOException("Incomplete route status snapshot");
        return packet.players();
    }

    public static Packet decodePacket(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length < 4 || bytes.length > MAX_PAYLOAD_BYTES)
            throw new IOException("Invalid route status payload size");
        DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes));
        if (input.readUnsignedShort() != MAGIC)
            throw new IOException("Unsupported route status payload");
        int version = input.readUnsignedByte();
        if (version < 1 || version > PROTOCOL_VERSION)
            throw new IOException("Unsupported route status payload version");
        long snapshotId = version == 1 ? 0 : input.readLong();
        int index = version == 1 ? 0 : input.readInt();
        int finalByte = version == 1 ? 1 : input.readUnsignedByte();
        if (index < 0 || finalByte > 1) throw new IOException("Invalid route status packet index");
        int count = input.readUnsignedByte();
        if (count > (version == 1 ? LEGACY_MAX_PLAYERS : MAX_PACKET_PLAYERS))
            throw new IOException("Too many entries in a route status packet");
        if (version != 1 && count == 0 && (index != 0 || finalByte != 1))
            throw new IOException("Invalid empty route status packet");
        List<PlayerRoute> players = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int nameLength = input.readUnsignedByte();
            int encodedNameLength = version == 1 ? nameLength : (nameLength * 6 + 7) / 8;
            int remainingFields = version == 1 ? 1 : version == 2 ? 5 : version == 3 ? 6 : 7;
            if (nameLength < 1 || nameLength > 16 || encodedNameLength > input.available() - remainingFields)
                throw new IOException("Invalid route status player name");
            byte[] nameBytes = input.readNBytes(encodedNameLength);
            String name = version == 1 ? new String(nameBytes, StandardCharsets.US_ASCII) : unpackName(nameBytes, nameLength);
            int status = input.readUnsignedByte();
            int ping = version == 1 ? -1 : input.readInt();
            boolean host = version >= 3 && input.readBoolean();
            boolean disconnected = version >= 4 && input.readBoolean();
            PlayerRoute player = new PlayerRoute(name, status, ping, host, disconnected);
            try {
                validate(player);
            } catch (IllegalArgumentException invalid) {
                throw new IOException("Invalid route status entry", invalid);
            }
            players.add(player);
        }
        if (input.available() != 0) throw new IOException("Trailing route status data");
        return new Packet(version, snapshotId, index, finalByte == 1, List.copyOf(players));
    }

    // Six bits cover Minecraft's 63 name characters, keeping v2 within the v1 packet-size limit.
    private static byte[] packName(String name) {
        byte[] bytes = new byte[(name.length() * 6 + 7) / 8];
        for (int i = 0; i < name.length(); i++) {
            int value = NAME_ALPHABET.indexOf(name.charAt(i));
            for (int bit = 0; bit < 6; bit++) {
                int position = i * 6 + bit;
                if ((value & (1 << (5 - bit))) != 0)
                    bytes[position / 8] |= (byte) (1 << (7 - position % 8));
            }
        }
        return bytes;
    }

    private static String unpackName(byte[] bytes, int length) throws IOException {
        StringBuilder name = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            int value = 0;
            for (int bit = 0; bit < 6; bit++) {
                int position = i * 6 + bit;
                value = (value << 1) | ((bytes[position / 8] >> (7 - position % 8)) & 1);
            }
            if (value >= NAME_ALPHABET.length()) throw new IOException("Invalid packed player name");
            name.append(NAME_ALPHABET.charAt(value));
        }
        int padding = bytes.length * 8 - length * 6;
        if (padding > 0 && (bytes[bytes.length - 1] & ((1 << padding) - 1)) != 0)
            throw new IOException("Invalid packed player name padding");
        return name.toString();
    }

    private static void validate(PlayerRoute player) {
        if (player == null || player.playerName() == null || player.playerName().isEmpty()
                || player.playerName().length() > 16 || !player.playerName().matches("[A-Za-z0-9_]+"))
            throw new IllegalArgumentException("Invalid route status player name");
        if (player.status() < NOT_TAILCAT || player.status() > DISCONNECTED)
            throw new IllegalArgumentException("Invalid route status code");
        if (player.pingMillis() < -1)
            throw new IllegalArgumentException("Invalid route status ping");
    }
}
