package net.minecraft.network.protocol.status;

public record ServerStatus(String description, java.util.Optional<String> players, boolean enforcesSecureChat) {
}
