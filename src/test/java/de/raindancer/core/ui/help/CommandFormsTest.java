package de.raindancer.core.ui.help;

import de.raindancer.core.platform.command.CommandNote;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class CommandFormsTest {

    private static List<CommandForm> forms(String command, String... options) {
        return CommandForms.of(CommandNote.of("Test", command, "Does a thing.", options));
    }

    @Test
    @DisplayName("a command with no options is one form with nothing to fill in")
    void bare() {
        assertThat(forms("spawn")).singleElement().satisfies(form -> {
            assertThat(form.fields()).isEmpty();
            assertThat(form.build(Map.of())).contains("/spawn");
        });
        assertThat(forms("rest", "(nothing)")).singleElement()
                .satisfies(form -> assertThat(form.build(Map.of())).contains("/rest"));
    }

    @Test
    @DisplayName("<required> and [optional] become fields; a missing required one builds nothing")
    void fields() {
        CommandForm pay = forms("pay", "<player> <amount> [what for]").getFirst();
        assertThat(pay.fields()).extracting(CommandForm.Field::label).containsExactly("player", "amount", "what for");
        assertThat(pay.fields()).extracting(CommandForm.Field::required).containsExactly(true, true, false);
        assertThat(pay.fields().getFirst().players()).isTrue();
        assertThat(pay.build(Map.of("f0", "Ada", "f1", "50"))).contains("/pay Ada 50");
        assertThat(pay.build(Map.of("f0", "Ada", "f1", "50", "f2", "rent for May"))).contains("/pay Ada 50 rent for May");
        assertThat(pay.build(Map.of("f0", "Ada"))).isEmpty();
    }

    @Test
    @DisplayName("words stay words, a|b is a choice, and top-level | splits into separate forms")
    void choicesAndAlternatives() {
        List<CommandForm> hire = forms("hire", "<player> <wage> <every> [job]", "list", "fire|quit <player>");
        assertThat(hire).hasSize(3);
        assertThat(hire.get(1).build(Map.of())).contains("/hire list");
        CommandForm fire = hire.get(2);
        assertThat(fire.fields().getFirst().choices()).containsExactly("fire", "quit");
        assertThat(fire.build(Map.of("f0", "quit", "f1", "Bo"))).contains("/hire quit Bo");

        List<CommandForm> token = forms("discordlink", "token <token> | token clear — console only");
        assertThat(token).hasSize(2);
        assertThat(token.get(1).build(Map.of())).contains("/discordlink token clear");
        assertThat(token.get(1).note()).isEqualTo("console only");
    }

    @Test
    @DisplayName("a whole usage in brackets is optional: the bare command is a form, then each alternative")
    void bracketedAlternatives() {
        List<CommandForm> loan = forms("loan", "[take <amount>]", "repay [amount|all]");
        assertThat(loan).extracting(form -> form.build(Map.of("f0", "100"))).contains(
                Optional.of("/loan"), Optional.of("/loan take 100"), Optional.of("/loan repay 100"));
        List<CommandForm> lottery = forms("lottery", "[buy [tickets] | buy <numbers…> | info]");
        assertThat(lottery).extracting(CommandForm::usage)
                .containsExactly("/lottery", "/lottery buy [tickets]", "/lottery buy <numbers…>", "/lottery info");
    }

    @Test
    @DisplayName("an optional choice may be left out, and a typed value with spaces is kept")
    void optionalChoice() {
        CommandForm flip = forms("coinflip", "<amount> [heads|tails]").getFirst();
        assertThat(flip.fields().get(1).required()).isFalse();
        assertThat(flip.build(Map.of("f0", "10", "f1", ""))).contains("/coinflip 10");
        assertThat(flip.build(Map.of("f0", "10", "f1", "tails"))).contains("/coinflip 10 tails");
    }

    @Test
    @DisplayName("a subcommand note keeps its words")
    void subcommand() {
        assertThat(forms("home set", "<name>").getFirst().build(Map.of("f0", "base"))).contains("/home set base");
    }

    @Test
    @DisplayName("a usage mentioned in an answer is matched to the longest declared command it starts with")
    void forUsage() {
        List<CommandNote> notes = List.of(CommandNote.of("Homes", "home", "Go home.", "[name]"),
                CommandNote.of("Homes", "home set", "Set one.", "<name>"),
                CommandNote.of("Economy", "pay", "Pay someone.", "<player> <amount>"));
        var set = CommandForms.forUsage("/home set <name>", notes).orElseThrow();
        assertThat(set.note().command()).isEqualTo("home set");
        assertThat(set.form().build(Map.of("f0", "base"))).contains("/home set base");
        var pay = CommandForms.forUsage("/pay <player> <amount>", notes).orElseThrow();
        assertThat(pay.form().fields()).hasSize(2);
        // Written without its arguments in the text: the command's own first form.
        assertThat(CommandForms.forUsage("/pay", notes).orElseThrow().form().fields()).hasSize(2);
        assertThat(CommandForms.forUsage("/fly", notes)).isEmpty();
    }

    @Test
    @DisplayName("an answer's commands are the ones in the text the reader actually gets")
    void mentioned() {
        var context = HelpContexts.fixed(Map.of("economy:features.pay", false));
        assertThat(HelpText.commandsMentioned("Type {cmd:/pay <player> <amount>} or {run:/balance}."
                + "{if:economy:features.pay} Or {cmd:/bill}.{/if} Again: {cmd:/pay}", context))
                .containsExactly("/pay <player> <amount>", "/balance", "/pay");
    }
}
