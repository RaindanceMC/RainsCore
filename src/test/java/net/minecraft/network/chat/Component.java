package net.minecraft.network.chat;

/** Stand-in for the server's class of that name, just enough for UnsignedChatTest. */
public interface Component {
    String text();

    static Component literal(String text) {
        return () -> text;
    }
}
