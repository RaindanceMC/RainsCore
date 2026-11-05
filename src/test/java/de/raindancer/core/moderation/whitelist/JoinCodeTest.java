package de.raindancer.core.moderation.whitelist;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class JoinCodeTest {

    @Test
    @DisplayName("codes are two blocks of five from an alphabet without look-alikes, and read back as themselves")
    void shape() {
        Random random = new Random(118);
        for (int i = 0; i < 200; i++) {
            String code = JoinCode.newCode(random);
            assertThat(code).matches("[A-HJ-NP-Z2-9]{5}-[A-HJ-NP-Z2-9]{5}").doesNotContain("O", "I", "L", "0", "1");
            assertThat(JoinCode.normalise(code)).contains(code);
        }
    }

    @Test
    @DisplayName("what people actually type is understood: lower case, spaces, no dash")
    void forgiving() {
        assertThat(JoinCode.normalise(" abcde 23456 ")).contains("ABCDE-23456");
        assertThat(JoinCode.normalise("abcde23456")).contains("ABCDE-23456");
        assertThat(JoinCode.normalise("ABCDE-2345")).isEmpty();
        assertThat(JoinCode.normalise("ABCDE-23450")).as("0 is not in the alphabet").isEmpty();
        assertThat(JoinCode.normalise(null)).isEmpty();
        assertThat(JoinCode.normalise("'; DROP TABLE--")).isEmpty();
    }

    @Test
    @DisplayName("only the JDK: the proxy shades this class on its own")
    void standsAlone() {
        assertThat(JoinCode.class.getDeclaredFields()).allSatisfy(field ->
                assertThat(field.getType().isPrimitive() || field.getType().getName().startsWith("java.")).isTrue());
    }
}
