package de.raindancer.core.ui.profile;

import de.raindancer.core.ui.dialog.DialogAnswers;
import de.raindancer.core.ui.dialog.DialogPage;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProfileDialogTest {

    private static final Instant NOW = Instant.parse("2026-11-08T18:00:00Z");
    private final Player viewer = mock(Player.class);
    private final OfflinePlayer subject = mock(OfflinePlayer.class);
    private final List<ProfileExtension> registered = new ArrayList<>();

    @BeforeEach
    void wire() {
        when(viewer.isOnline()).thenReturn(true);
        when(viewer.getUniqueId()).thenReturn(UUID.randomUUID());
        DialogPage.runtime(new DialogPage.Runtime() {
            @Override
            public void show(Player player, DialogPage page) {
            }

            @Override
            public void onPlayerThread(Player player, Runnable task) {
                task.run();
            }
        });
    }

    @AfterEach
    void unwire() {
        DialogPage.runtime(null);
        registered.forEach(ProfileExtensions::unregister);
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    private void register(ProfileExtension extension) {
        registered.add(extension);
        ProfileExtensions.register(extension);
    }

    private static ItemStack icon(String name, String... lore) {
        ItemStack icon = mock(ItemStack.class);
        when(icon.effectiveName()).thenReturn(Component.text(name));
        when(icon.lore()).thenReturn(java.util.Arrays.stream(lore).map(line -> (Component) Component.text(line)).toList());
        return icon;
    }

    @Test
    @DisplayName("somebody never seen says so; somebody on is online; somebody off says when, and what they played")
    void whoTheyAre() {
        assertThat(ProfileDialog.facts(false, false, false, 0, null, NOW)).containsExactly("<gray>This server has never seen them.");
        assertThat(ProfileDialog.facts(true, true, false, 0, null, NOW)).first().asString().contains("Online right now");
        List<String> away = ProfileDialog.facts(true, false, false, NOW.minus(Duration.ofHours(3)).toEpochMilli(),
                new ProfileDialog.Played(Duration.ofHours(10), Duration.ofHours(8), Duration.ofHours(2)), NOW);
        assertThat(away.get(0)).contains("Last seen").contains("3 hours");
        assertThat(String.join("\n", away)).contains("Played").contains("10 hours").contains("Active").contains("AFK");
    }

    @Test
    @DisplayName("somebody vanished from the viewer reads as gone since their last login, never as online")
    void vanishedLooksGone() {
        List<String> facts = ProfileDialog.facts(true, true, true, NOW.minus(Duration.ofMinutes(5)).toEpochMilli(), null, NOW);
        assertThat(String.join("\n", facts)).doesNotContain("Online").contains("Last seen");
    }

    @Test
    @DisplayName("what the installed modules offer becomes the window's buttons, its name the label, its lore the tooltip")
    void modulesOfferButtons() {
        AtomicInteger messaged = new AtomicInteger();
        register((who, about, parent) -> new ProfileButton(icon("Message", "Opens a box to write in."),
                click -> messaged.incrementAndGet()));
        register((who, about, parent) -> null);
        register((who, about, parent) -> {
            throw new IllegalStateException("one contributor's bug");
        });

        DialogPage page = ProfileDialog.page(viewer, null, UUID.randomUUID(), "Notch", subject, List.of("<green>Online"));
        assertThat(plain(page.title())).isEqualTo("Profile › Notch");
        assertThat(page.button("Message").tooltip()).isNotNull();
        assertThat(plain(page.button("Message").tooltip())).contains("Opens a box to write in.");
        assertThat(page.pressable()).hasSize(1);

        page.button("Message").press(viewer, DialogAnswers.none());
        assertThat(messaged).hasValue(1);
    }

    @Test
    @DisplayName("with nothing installed the window is just who they are, and Close")
    void nothingInstalled() {
        DialogPage page = ProfileDialog.page(viewer, null, UUID.randomUUID(), "Notch", subject, List.of("<gray>x"));
        assertThat(page.pressable()).extracting(button -> plain(button.label())).containsExactly("Close");
    }
}
