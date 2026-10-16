package de.raindancer.core.social.desk;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceDesksTest {

    @AfterEach
    void clear() {
        ServiceDesks.clear();
    }

    private record Offer(String name) implements DeskOffer {

        @Override
        public ItemStack icon(Player viewer) {
            return null;
        }

        @Override
        public void use(Player viewer, Runnable backToDesk) {
        }
    }

    @Test
    @DisplayName("each desk lists what was offered at it, in the order it was offered, once")
    void offers() {
        Offer bets = new Offer("bets");
        Offer items = new Offer("items");
        ServiceDesks.offer(ServiceDesk.INSURANCE, bets);
        ServiceDesks.offer(ServiceDesk.INSURANCE, items);
        ServiceDesks.offer(ServiceDesk.INSURANCE, bets);

        assertThat(ServiceDesks.offers(ServiceDesk.INSURANCE)).containsExactly(bets, items);
    }

    @Test
    @DisplayName("an offer withdrawn is gone; a plugin disabled without withdrawing loses its offers too")
    void withdrawn() {
        Offer bets = new Offer("bets");
        Offer items = new Offer("items");
        ServiceDesks.offer(ServiceDesk.INSURANCE, bets);
        ServiceDesks.offer(ServiceDesk.INSURANCE, items);

        ServiceDesks.withdraw(bets);
        assertThat(ServiceDesks.offers(ServiceDesk.INSURANCE)).containsExactly(items);

        assertThat(ServiceDesks.forgetFrom(Offer.class.getClassLoader())).isEqualTo(1);
        assertThat(ServiceDesks.offers(ServiceDesk.INSURANCE)).isEmpty();
    }

    @Test
    @DisplayName("in person only is off until Core's setting says so, and per desk")
    void inPerson() {
        assertThat(ServiceDesks.inPersonOnly(ServiceDesk.INSURANCE)).isFalse();
        ServiceDesks.inPersonOnly(ServiceDesk.INSURANCE, true);
        assertThat(ServiceDesks.inPersonOnly(ServiceDesk.INSURANCE)).isTrue();
        ServiceDesks.clear();
        assertThat(ServiceDesks.inPersonOnly(ServiceDesk.INSURANCE)).isFalse();
    }

    @Test
    @DisplayName("a desk is read back from what an attendant carries, and nothing else is a desk")
    void read() {
        assertThat(ServiceDesk.read("INSURANCE")).contains(ServiceDesk.INSURANCE);
        assertThat(ServiceDesk.read("insurance")).contains(ServiceDesk.INSURANCE);
        assertThat(ServiceDesk.read("bank")).contains(ServiceDesk.BANK);
        assertThat(ServiceDesk.read("casino")).isEmpty();
        assertThat(ServiceDesk.read(null)).isEmpty();
    }

    @Test
    @DisplayName("each desk wears the skins Core's settings give it, none set is the suit")
    void skins() {
        assertThat(ServiceDesks.skins(ServiceDesk.BANK)).isEmpty();
        ServiceDesks.skins(ServiceDesk.BANK, "mineskin:d01b7d1a");
        ServiceDesks.skins(ServiceDesk.INSURANCE, "mineskin:5bf0f0b8, mineskin:01b7d65b");
        assertThat(ServiceDesks.skins(ServiceDesk.BANK)).isEqualTo("mineskin:d01b7d1a");
        assertThat(ServiceDesks.skins(ServiceDesk.INSURANCE)).isEqualTo("mineskin:5bf0f0b8, mineskin:01b7d65b");
        ServiceDesks.clear();
        assertThat(ServiceDesks.skins(ServiceDesk.BANK)).isEmpty();
    }
}
