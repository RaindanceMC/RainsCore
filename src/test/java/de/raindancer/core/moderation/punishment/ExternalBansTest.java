package de.raindancer.core.moderation.punishment;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ExternalBansTest {

    @AfterEach
    void clear() {
        ExternalBans.clear();
    }

    @Test
    @DisplayName("without a plugin that bans, a ban from outside is not given and says why")
    void nobodyBans() {
        assertThat(ExternalBans.available()).isFalse();
        assertThat(ExternalBans.ban(UUID.randomUUID(), "Griefer", "Hush", "raid"))
                .isEqualTo(ExternalBans.Outcome.NOBODY);
        assertThat(ExternalBans.unban(UUID.randomUUID(), "Griefer", "Hush", "appeal"))
                .isEqualTo(ExternalBans.Outcome.NOBODY);
    }

    @Test
    @DisplayName("a ban and an unban go to whoever bans, with who asked and why, and their answer comes back")
    void handedOver() {
        List<String> asked = new ArrayList<>();
        UUID griefer = UUID.randomUUID();
        ExternalBans.Banner banner = new ExternalBans.Banner() {
            @Override
            public ExternalBans.Outcome ban(UUID subject, String name, String by, String reason) {
                asked.add("ban " + name + " by " + by + ": " + reason);
                return ExternalBans.Outcome.DONE;
            }

            @Override
            public ExternalBans.Outcome unban(UUID subject, String name, String by, String reason) {
                asked.add("unban " + name + " by " + by + ": " + reason);
                return ExternalBans.Outcome.ALREADY;
            }
        };
        ExternalBans.provide(banner);

        assertThat(ExternalBans.available()).isTrue();
        assertThat(ExternalBans.ban(griefer, "Griefer", "Hush", "raid")).isEqualTo(ExternalBans.Outcome.DONE);
        assertThat(ExternalBans.unban(griefer, "Griefer", "Hush", "appeal")).isEqualTo(ExternalBans.Outcome.ALREADY);
        assertThat(asked).containsExactly("ban Griefer by Hush: raid", "unban Griefer by Hush: appeal");

        ExternalBans.withdraw(banner);
        assertThat(ExternalBans.available()).isFalse();
    }

    @Test
    @DisplayName("nobody is banned without an id, and the banner is not even asked")
    void noSubject() {
        boolean[] asked = {false};
        ExternalBans.provide(always(ExternalBans.Outcome.DONE, asked));
        assertThat(ExternalBans.ban(null, "Griefer", "Hush", "raid")).isEqualTo(ExternalBans.Outcome.FAILED);
        assertThat(asked[0]).isFalse();
    }

    @Test
    @DisplayName("a banner that throws or answers nothing costs that one ban, not the caller")
    void brokenBanner() {
        ExternalBans.provide(new ExternalBans.Banner() {
            @Override
            public ExternalBans.Outcome ban(UUID subject, String name, String by, String reason) {
                throw new IllegalStateException("broken");
            }

            @Override
            public ExternalBans.Outcome unban(UUID subject, String name, String by, String reason) {
                return null;
            }
        });
        assertThat(ExternalBans.ban(UUID.randomUUID(), "Griefer", "Hush", "raid")).isEqualTo(ExternalBans.Outcome.FAILED);
        assertThat(ExternalBans.unban(UUID.randomUUID(), "Griefer", "Hush", "raid")).isEqualTo(ExternalBans.Outcome.FAILED);
    }

    @Test
    @DisplayName("withdrawing somebody else's banner leaves the current one in place")
    void withdrawOnlyYourOwn() {
        ExternalBans.Banner current = always(ExternalBans.Outcome.DONE, new boolean[1]);
        ExternalBans.provide(current);
        ExternalBans.withdraw(always(ExternalBans.Outcome.DONE, new boolean[1]));
        assertThat(ExternalBans.available()).isTrue();
    }

    @Test
    @DisplayName("a plugin's banner is forgotten when that plugin's code goes, nobody else's")
    void forgottenWithItsPlugin() {
        ExternalBans.provide(always(ExternalBans.Outcome.DONE, new boolean[1]));
        assertThat(ExternalBans.forgetFrom(new ClassLoader() { })).isZero();
        assertThat(ExternalBans.forgetFrom(ExternalBansTest.class.getClassLoader())).isEqualTo(1);
        assertThat(ExternalBans.available()).isFalse();
    }

    private static ExternalBans.Banner always(ExternalBans.Outcome outcome, boolean[] asked) {
        return new ExternalBans.Banner() {
            @Override
            public ExternalBans.Outcome ban(UUID subject, String name, String by, String reason) {
                asked[0] = true;
                return outcome;
            }

            @Override
            public ExternalBans.Outcome unban(UUID subject, String name, String by, String reason) {
                asked[0] = true;
                return outcome;
            }
        };
    }
}
