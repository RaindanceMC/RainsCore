package de.raindancer.core.ui.messages;

import de.raindancer.core.CoreConfig;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/** There is one wording. Plugins built while there were two still hand over their serious file. */
class OneToneTest {

    @TempDir
    Path folder;

    private static InputStream yaml(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    private Messages loaded() {
        Messages messages = new Messages(folder.resolve("messages.yml"));
        messages.load(yaml("""
                prefix: ""
                home:
                  gone: "That home no longer exists."
                """));
        return messages;
    }

    @Test
    @DisplayName("a serious file from an older plugin is closed and changes nothing")
    void seriousFileIsIgnored() {
        Messages messages = loaded();
        AtomicBoolean closed = new AtomicBoolean();
        InputStream serious = new ByteArrayInputStream("home:\n  gone: \"Gone.\"\n".getBytes(StandardCharsets.UTF_8)) {
            @Override
            public void close() throws IOException {
                closed.set(true);
                super.close();
            }
        };
        assertThat(messages.seriousFrom(serious)).isZero();
        assertThat(closed).isTrue();
        assertThat(messages.seriousFrom(null)).isZero();
        assertThat(messages.seriousFrom(yaml("home: [unclosed"))).isZero();
        assertThat(messages.problems()).isEmpty();
        assertThat(PlainTextComponentSerializer.plainText().serialize(messages.get("home.gone")))
                .isEqualTo("That home no longer exists.");
    }

    @Test
    @DisplayName("there is no tone to choose, neither in Messages nor in Core's settings")
    void noToneSetting() {
        assertThat(Messages.class.getDeclaredClasses())
                .extracting(Class::getSimpleName).doesNotContain("Tone");
        assertThat(Arrays.stream(Messages.class.getMethods()).map(m -> m.getName())).doesNotContain("tone");
        assertThat(Arrays.stream(CoreConfig.class.getRecordComponents()).map(c -> c.getName()))
                .doesNotContain("messageTone");
    }

    @Test
    @DisplayName("without a running Core, a built-in line is said as written")
    void builtInWithoutCore() {
        assertThat(PlainTextComponentSerializer.plainText().serialize(
                Messages.spoken("nothing.here", "<red>Not a sound. Silence it is.")))
                .isEqualTo("Not a sound. Silence it is.");
    }
}
