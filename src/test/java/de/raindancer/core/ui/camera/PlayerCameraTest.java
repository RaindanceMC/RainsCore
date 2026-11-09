package de.raindancer.core.ui.camera;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSetCameraPacket;
import net.minecraft.world.entity.Entity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("a player's camera: looking through another entity and back")
class PlayerCameraTest {

    /** What the server's connection is: something with send(Packet). */
    public static final class Listener {
        public final List<Object> sent = new ArrayList<>();

        public void send(Packet<?> packet) {
            sent.add(packet);
        }
    }

    /** The server's player: an entity with a public connection. */
    public static final class ServerPlayer extends Entity {
        public final Listener connection = new Listener();

        public ServerPlayer(int id) {
            super(id);
        }
    }

    /** What Bukkit hands out: a wrapper with getHandle(). */
    public static final class Craft {
        private final Object handle;

        public Craft(Object handle) {
            this.handle = handle;
        }

        public Object getHandle() {
            return handle;
        }
    }

    private final PlayerCamera camera = new PlayerCamera(getClass().getClassLoader());

    @Test
    @DisplayName("fits a server with the camera packet")
    void works() {
        assertThat(camera.works()).isTrue();
    }

    @Test
    @DisplayName("looking through an entity sends that entity's camera packet to the viewer only")
    void look() {
        ServerPlayer viewer = new ServerPlayer(1);
        Entity display = new Entity(42);

        assertThat(camera.send(new Craft(viewer), new Craft(display))).isTrue();

        assertThat(viewer.connection.sent).singleElement()
                .isInstanceOfSatisfying(ClientboundSetCameraPacket.class,
                        packet -> assertThat(packet.cameraId()).isEqualTo(42));
    }

    @Test
    @DisplayName("back to their own eyes is the camera packet for the viewer themselves")
    void reset() {
        ServerPlayer viewer = new ServerPlayer(7);

        assertThat(camera.send(new Craft(viewer), new Craft(viewer))).isTrue();

        assertThat(((ClientboundSetCameraPacket) viewer.connection.sent.getFirst()).cameraId()).isEqualTo(7);
    }

    @Test
    @DisplayName("something that is no server object is refused, never thrown")
    void misfit() {
        assertThat(camera.send("not a player", new Craft(new Entity(1)))).isFalse();
        assertThat(camera.send(new Craft(new ServerPlayer(1)), null)).isFalse();
    }

    @Test
    @DisplayName("on a server without the packet it stands down and says so")
    void standsDown() {
        PlayerCamera none = new PlayerCamera(new ClassLoader(null) {
        });

        assertThat(none.works()).isFalse();
        assertThat(none.send(new Craft(new ServerPlayer(1)), new Craft(new Entity(2)))).isFalse();
    }
}
