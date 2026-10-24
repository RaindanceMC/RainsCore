package de.raindancer.core.ui.help;

import de.raindancer.core.CoreConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CoreGuideTest {

    @Test
    @DisplayName("Core's own pages read, and every setting they quote exists")
    void coreGuide() {
        assertThat(HelpLint.of(Path.of("src/main/resources/rains-help"))
                .settings(CoreConfig.class, CoreConfig.DEFAULTS)
                .settings(HelpSettings.class, HelpSettings.DEFAULTS)
                .commands(List.of("help", "commands", "changelog", "hub", "settings", "maintenance", "documents",
                        "network", "prefix"))
                .problems()).isEmpty();
    }

    @Test
    @DisplayName("a placeholder inside a placeholder is refused")
    void nested() {
        List<String> problems = new java.util.ArrayList<>();
        HelpText.render("{run:/{setting:core:hub-command}}", HelpContexts.fixed(java.util.Map.of()), problems);
        assertThat(problems).anyMatch(problem -> problem.contains("cannot hold another"));
    }
}
