package de.raindancer.core.platform.command;

import de.raindancer.core.ui.hub.HubCommand;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.Commands;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class CoreCommandsHubTest {

    private static void reset() throws Exception {
        Field taken = CoreCommands.class.getDeclaredField("HUB_TAKEN");
        taken.setAccessible(true);
        ((AtomicBoolean) taken.get(null)).set(false);
    }

    @Test
    @DisplayName("/hub is registered with /smp as alias, once; every later plugin is declined")
    void registersOnce() throws Exception {
        reset();
        Commands first = mock(Commands.class);
        Commands second = mock(Commands.class);

        assertThat(CoreCommands.hub(first)).isTrue();
        assertThat(CoreCommands.hub(second)).isFalse();

        ArgumentCaptor<BasicCommand> handler = ArgumentCaptor.forClass(BasicCommand.class);
        verify(first).register(eq("hub"), any(String.class), eq(List.of("smp")), handler.capture());
        assertThat(handler.getValue()).isInstanceOf(HubCommand.class);
        verify(second, never()).register(any(String.class), any(String.class), any(List.class),
                any(BasicCommand.class));
        reset();
    }
}
