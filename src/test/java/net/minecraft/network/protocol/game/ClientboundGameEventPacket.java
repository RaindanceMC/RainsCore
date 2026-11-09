package net.minecraft.network.protocol.game;

import net.minecraft.network.protocol.Packet;

public class ClientboundGameEventPacket implements Packet<Object> {

    public static final Type CHANGE_GAME_MODE = new Type(3);

    private final Type event;
    private final float param;

    public ClientboundGameEventPacket(Type event, float param) {
        this.event = event;
        this.param = param;
    }

    public Type getEvent() {
        return event;
    }

    public float getParam() {
        return param;
    }

    public static class Type {
        public Type(int id) {
        }
    }
}
