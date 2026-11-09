package net.minecraft.network.protocol.game;

import net.minecraft.network.protocol.Packet;
import net.minecraft.world.entity.Entity;

public final class ClientboundSetCameraPacket implements Packet<Object> {

    private final int cameraId;

    public ClientboundSetCameraPacket(Entity camera) {
        this.cameraId = camera.getId();
    }

    public int cameraId() {
        return cameraId;
    }
}
