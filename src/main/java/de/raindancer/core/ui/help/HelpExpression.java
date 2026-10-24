package de.raindancer.core.ui.help;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * A {@code when:} condition: whether a section, a topic or an inline {@code {if:…}} applies on this server.
 *
 * <pre>
 * economy:features.pay                      the setting is on / non-zero / non-empty
 * !economy:features.bills                   it is not
 * economy:pay.tax-percent &gt; 0              numbers compare as numbers
 * claims:mode == personal                   everything else case-blind ('quoted' when it has spaces)
 * &#64;perm:rainseconomy.admin                 the reader has the permission
 * &#64;plugin:RainsShops  &#64;module:shops        that is installed here
 * a &amp;&amp; b || c                              ! before &amp;&amp; before ||; no brackets
 * </pre>
 *
 * <p>No brackets, deliberately. A help page that needs them is describing a configuration nobody can
 * explain to a player either; two sections with simpler conditions read better.
 *
 * <p>Parsed when the topic is read, so a broken condition is a problem in {@code /help check} and a failed
 * module build — never a page that silently shows or hides the wrong thing.
 */
public final class HelpExpression {

    private enum Op { EQ, NE, GT, GE, LT, LE }

    private sealed interface Operand permits SettingRef, Perm, Plugin, Module {
    }

    private record SettingRef(String key) implements Operand {
    }

    private record Perm(String node) implements Operand {
    }

    private record Plugin(String name) implements Operand {
    }

    private record Module(String id) implements Operand {
    }

    private record Atom(boolean negated, Operand operand, Op op, String literal) {
    }

    private final String source;
    /** Alternatives, each a list of atoms that must all hold. */
    private final List<List<Atom>> alternatives;

    private HelpExpression(String source, List<List<Atom>> alternatives) {
        this.source = source;
        this.alternatives = alternatives;
    }

    /** @throws IllegalArgumentException saying what is wrong, for a problem report */
    public static HelpExpression parse(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("an empty condition");
        }
        List<String> tokens = tokens(expression);
        List<List<Atom>> alternatives = new ArrayList<>();
        List<Atom> current = new ArrayList<>();
        int index = 0;
        boolean expectAtom = true;
        while (index < tokens.size()) {
            String token = tokens.get(index);
            if (expectAtom) {
                boolean negated = false;
                while (token.equals("!")) {
                    negated = !negated;
                    index++;
                    if (index >= tokens.size()) {
                        throw new IllegalArgumentException("'!' with nothing after it in '" + expression + "'");
                    }
                    token = tokens.get(index);
                }
                if (isSymbol(token)) {
                    throw new IllegalArgumentException("expected a setting, @perm, @plugin or @module but found '"
                            + token + "' in '" + expression + "'");
                }
                Operand operand = operand(token, expression);
                index++;
                Op op = null;
                String literal = null;
                if (index < tokens.size() && op(tokens.get(index)) != null) {
                    op = op(tokens.get(index));
                    index++;
                    if (index >= tokens.size() || isSymbol(tokens.get(index))) {
                        throw new IllegalArgumentException("'" + token + "' is compared with nothing in '"
                                + expression + "'");
                    }
                    if (!(operand instanceof SettingRef)) {
                        throw new IllegalArgumentException("only a setting can be compared, not '" + token + "'");
                    }
                    literal = unquote(tokens.get(index));
                    index++;
                }
                current.add(new Atom(negated, operand, op, literal));
                expectAtom = false;
            } else {
                switch (token) {
                    case "&&" -> expectAtom = true;
                    case "||" -> {
                        alternatives.add(List.copyOf(current));
                        current = new ArrayList<>();
                        expectAtom = true;
                    }
                    default -> throw new IllegalArgumentException("expected && or || but found '" + token
                            + "' in '" + expression + "'");
                }
                index++;
            }
        }
        if (expectAtom) {
            throw new IllegalArgumentException("'" + expression + "' ends where a condition should follow");
        }
        alternatives.add(List.copyOf(current));
        return new HelpExpression(expression.strip(), List.copyOf(alternatives));
    }

    /** Whether it holds on this server, for this reader. A setting nobody declares counts as off. */
    public boolean test(HelpContext context) {
        for (List<Atom> all : alternatives) {
            boolean holds = true;
            for (Atom atom : all) {
                if (!holds(atom, context)) {
                    holds = false;
                    break;
                }
            }
            if (holds) {
                return true;
            }
        }
        return false;
    }

    /** The setting keys it reads, in order — for a lint to check they exist. */
    public Set<String> settings() {
        Set<String> keys = new LinkedHashSet<>();
        for (List<Atom> all : alternatives) {
            for (Atom atom : all) {
                if (atom.operand() instanceof SettingRef ref) {
                    keys.add(ref.key());
                }
            }
        }
        return keys;
    }

    @Override
    public String toString() {
        return source;
    }

    private static boolean holds(Atom atom, HelpContext context) {
        boolean result = switch (atom.operand()) {
            case Perm perm -> context.has(perm.node());
            case Plugin plugin -> context.hasPlugin(plugin.name());
            case Module module -> context.hasModule(module.id());
            case SettingRef ref -> {
                Optional<Object> value = context.setting(ref.key());
                if (atom.op() == null) {
                    yield value.map(HelpValues::truthy).orElse(false);
                }
                yield value.map(found -> compare(found, atom.op(), atom.literal())).orElse(false);
            }
        };
        return atom.negated() != result;
    }

    private static boolean compare(Object value, Op op, String literal) {
        Optional<Double> left = HelpValues.numeric(value);
        Optional<Double> right = HelpValues.numeric(literal);
        if (left.isPresent() && right.isPresent()) {
            int order = Double.compare(left.get(), right.get());
            return switch (op) {
                case EQ -> order == 0;
                case NE -> order != 0;
                case GT -> order > 0;
                case GE -> order >= 0;
                case LT -> order < 0;
                case LE -> order <= 0;
            };
        }
        String shown = value instanceof Boolean flag ? String.valueOf(flag) : HelpValues.display(value);
        String a = shown.strip().toLowerCase(Locale.ROOT).replace('_', ' ');
        String b = literal.strip().toLowerCase(Locale.ROOT).replace('_', ' ');
        if (value instanceof Boolean flag) {
            // "== on" and "== true" are the same question.
            b = switch (b) {
                case "on", "yes" -> "true";
                case "off", "no" -> "false";
                default -> b;
            };
            a = String.valueOf(flag);
        }
        int order = a.compareTo(b);
        return switch (op) {
            case EQ -> order == 0;
            case NE -> order != 0;
            case GT -> order > 0;
            case GE -> order >= 0;
            case LT -> order < 0;
            case LE -> order <= 0;
        };
    }

    private static Operand operand(String token, String expression) {
        if (token.startsWith("@")) {
            int colon = token.indexOf(':');
            String kind = colon < 0 ? token.substring(1) : token.substring(1, colon);
            String value = colon < 0 ? "" : token.substring(colon + 1);
            if (value.isBlank()) {
                throw new IllegalArgumentException("'" + token + "' names nothing in '" + expression + "'");
            }
            return switch (kind.toLowerCase(Locale.ROOT)) {
                case "perm", "permission" -> new Perm(value);
                case "plugin" -> new Plugin(value);
                case "module" -> new Module(value);
                default -> throw new IllegalArgumentException("'@" + kind + "' is not a thing a condition can ask "
                        + "about (use @perm:, @plugin: or @module:) in '" + expression + "'");
            };
        }
        return new SettingRef(token);
    }

    private static Op op(String token) {
        return switch (token) {
            case "==", "=" -> Op.EQ;
            case "!=" -> Op.NE;
            case ">" -> Op.GT;
            case ">=" -> Op.GE;
            case "<" -> Op.LT;
            case "<=" -> Op.LE;
            default -> null;
        };
    }

    private static boolean isSymbol(String token) {
        return token.equals("&&") || token.equals("||") || token.equals("!") || op(token) != null;
    }

    private static String unquote(String token) {
        if (token.length() >= 2 && (token.startsWith("'") && token.endsWith("'")
                || token.startsWith("\"") && token.endsWith("\""))) {
            return token.substring(1, token.length() - 1);
        }
        return token;
    }

    private static List<String> tokens(String expression) {
        List<String> tokens = new ArrayList<>();
        int index = 0;
        int length = expression.length();
        while (index < length) {
            char c = expression.charAt(index);
            if (Character.isWhitespace(c)) {
                index++;
                continue;
            }
            if (c == '\'' || c == '"') {
                int end = expression.indexOf(c, index + 1);
                if (end < 0) {
                    throw new IllegalArgumentException("a quote is never closed in '" + expression + "'");
                }
                tokens.add(expression.substring(index, end + 1));
                index = end + 1;
                continue;
            }
            String two = index + 1 < length ? expression.substring(index, index + 2) : "";
            if (two.equals("&&") || two.equals("||") || two.equals("==") || two.equals("!=")
                    || two.equals(">=") || two.equals("<=")) {
                tokens.add(two);
                index += 2;
                continue;
            }
            if (c == '!' || c == '>' || c == '<' || c == '=') {
                tokens.add(String.valueOf(c));
                index++;
                continue;
            }
            int start = index;
            while (index < length) {
                char d = expression.charAt(index);
                if (Character.isWhitespace(d) || d == '!' || d == '>' || d == '<' || d == '=' || d == '&'
                        || d == '|' || d == '\'' || d == '"') {
                    break;
                }
                index++;
            }
            if (start == index) {
                throw new IllegalArgumentException("'" + c + "' on its own in '" + expression + "' — did you mean "
                        + c + c + "?");
            }
            tokens.add(expression.substring(start, index));
        }
        return tokens;
    }
}
