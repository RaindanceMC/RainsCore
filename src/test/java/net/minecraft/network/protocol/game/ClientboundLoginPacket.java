package net.minecraft.network.protocol.game;

/** Stand-in, shaped like the server's: the flag is one field among others. */
public record ClientboundLoginPacket(int playerId, boolean hardcore, java.util.Set<String> levels,
                                     boolean onlineMode, boolean enforcesSecureChat) {
}
