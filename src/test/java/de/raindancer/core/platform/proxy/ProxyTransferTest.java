package de.raindancer.core.platform.proxy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProxyTransferTest {

    @Test
    @DisplayName("the Connect message is the sub-channel and the server's name, both as Java's writeUTF")
    void connectPayload() throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(ProxyTransfer.connectPayload("arena")));

        assertThat(in.readUTF()).isEqualTo("Connect");
        assertThat(in.readUTF()).isEqualTo("arena");
        assertThat(in.available()).isZero();
    }

    @Test
    @DisplayName("a blank server name is refused rather than sent")
    void blankRefused() {
        assertThatThrownBy(() -> ProxyTransfer.connectPayload(" ")).isInstanceOf(IllegalArgumentException.class);
    }
}
