package de.raindancer.core.ui.badge;

import net.kyori.adventure.text.format.TextColor;

import java.awt.image.BufferedImage;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Draws a rank badge: the label in bold white block letters on a bar of the rank's gradient, a light rim on top and a
 * dark one below, the corners taken off by two pixels and one. The letters are a 5×7 font drawn at twice its size.
 */
public final class BadgeArt {

    /** Java draws it 9 pixels tall over the line; twice that and a bit, so the letters keep a rim around them. */
    public static final int JAVA_HEIGHT = 20;
    public static final int BEDROCK_HEIGHT = 16;
    /** Bar either side of the letters. */
    public static final int PAD = 4;
    public static final String SUPPORTED = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789+-!?&'. ";

    private static final Map<Character, String[]> FONT = Map.ofEntries(
            Map.entry('A', new String[]{"01110", "10001", "10001", "11111", "10001", "10001", "10001"}),
            Map.entry('B', new String[]{"11110", "10001", "10001", "11110", "10001", "10001", "11110"}),
            Map.entry('C', new String[]{"01111", "10000", "10000", "10000", "10000", "10000", "01111"}),
            Map.entry('D', new String[]{"11110", "10001", "10001", "10001", "10001", "10001", "11110"}),
            Map.entry('E', new String[]{"11111", "10000", "10000", "11110", "10000", "10000", "11111"}),
            Map.entry('F', new String[]{"11111", "10000", "10000", "11110", "10000", "10000", "10000"}),
            Map.entry('G', new String[]{"01111", "10000", "10000", "10011", "10001", "10001", "01111"}),
            Map.entry('H', new String[]{"10001", "10001", "10001", "11111", "10001", "10001", "10001"}),
            Map.entry('I', new String[]{"111", "010", "010", "010", "010", "010", "111"}),
            Map.entry('J', new String[]{"00111", "00001", "00001", "00001", "00001", "10001", "01110"}),
            Map.entry('K', new String[]{"10001", "10010", "10100", "11000", "10100", "10010", "10001"}),
            Map.entry('L', new String[]{"10000", "10000", "10000", "10000", "10000", "10000", "11111"}),
            Map.entry('M', new String[]{"10001", "11011", "10101", "10101", "10001", "10001", "10001"}),
            Map.entry('N', new String[]{"10001", "11001", "10101", "10011", "10001", "10001", "10001"}),
            Map.entry('O', new String[]{"01110", "10001", "10001", "10001", "10001", "10001", "01110"}),
            Map.entry('P', new String[]{"11110", "10001", "10001", "11110", "10000", "10000", "10000"}),
            Map.entry('Q', new String[]{"01110", "10001", "10001", "10001", "10101", "10010", "01101"}),
            Map.entry('R', new String[]{"11110", "10001", "10001", "11110", "10100", "10010", "10001"}),
            Map.entry('S', new String[]{"01111", "10000", "10000", "01110", "00001", "00001", "11110"}),
            Map.entry('T', new String[]{"11111", "00100", "00100", "00100", "00100", "00100", "00100"}),
            Map.entry('U', new String[]{"10001", "10001", "10001", "10001", "10001", "10001", "01110"}),
            Map.entry('V', new String[]{"10001", "10001", "10001", "10001", "01010", "01010", "00100"}),
            Map.entry('W', new String[]{"10001", "10001", "10001", "10101", "10101", "11011", "10001"}),
            Map.entry('X', new String[]{"10001", "10001", "01010", "00100", "01010", "10001", "10001"}),
            Map.entry('Y', new String[]{"10001", "10001", "01010", "00100", "00100", "00100", "00100"}),
            Map.entry('Z', new String[]{"11111", "00001", "00010", "00100", "01000", "10000", "11111"}),
            Map.entry('0', new String[]{"01110", "10001", "10011", "10101", "11001", "10001", "01110"}),
            Map.entry('1', new String[]{"010", "110", "010", "010", "010", "010", "111"}),
            Map.entry('2', new String[]{"01110", "10001", "00001", "00110", "01000", "10000", "11111"}),
            Map.entry('3', new String[]{"11110", "00001", "00001", "01110", "00001", "00001", "11110"}),
            Map.entry('4', new String[]{"10010", "10010", "10010", "11111", "00010", "00010", "00010"}),
            Map.entry('5', new String[]{"11111", "10000", "11110", "00001", "00001", "10001", "01110"}),
            Map.entry('6', new String[]{"01110", "10000", "10000", "11110", "10001", "10001", "01110"}),
            Map.entry('7', new String[]{"11111", "00001", "00010", "00100", "01000", "01000", "01000"}),
            Map.entry('8', new String[]{"01110", "10001", "10001", "01110", "10001", "10001", "01110"}),
            Map.entry('9', new String[]{"01110", "10001", "10001", "01111", "00001", "00001", "01110"}),
            Map.entry('+', new String[]{"000", "000", "010", "111", "010", "000", "000"}),
            Map.entry('-', new String[]{"000", "000", "000", "111", "000", "000", "000"}),
            Map.entry('!', new String[]{"1", "1", "1", "1", "1", "0", "1"}),
            Map.entry('?', new String[]{"01110", "10001", "00001", "00110", "00100", "00000", "00100"}),
            Map.entry('&', new String[]{"01100", "10010", "10100", "01000", "10101", "10010", "01101"}),
            Map.entry('\'', new String[]{"1", "1", "0", "0", "0", "0", "0"}),
            Map.entry('.', new String[]{"0", "0", "0", "0", "0", "0", "1"}),
            Map.entry(' ', new String[]{"00", "00", "00", "00", "00", "00", "00"}));

    private BadgeArt() {
    }

    /** The drawing of one character, or null when the font has none. */
    public static String[] glyph(char c) {
        return FONT.get(c);
    }

    /** How wide a label's badge is, in pixels of the art. */
    public static int width(String label) {
        int width = 2 * PAD;
        for (int i = 0; i < label.length(); i++) {
            width += advance(label.charAt(i)) + (i > 0 ? 2 : 0);
        }
        return width;
    }

    public static BufferedImage java(String label, List<TextColor> colours) {
        return draw(label, colours, JAVA_HEIGHT, 3);
    }

    public static BufferedImage bedrock(String label, List<TextColor> colours) {
        return draw(label, colours, BEDROCK_HEIGHT, 1);
    }

    /** Twice the glyph's width, and one more for the bold. */
    private static int advance(char c) {
        return FONT.get(c)[0].length() * 2 + 1;
    }

    private static BufferedImage draw(String label, List<TextColor> colours, int height, int top) {
        int width = width(label);
        BufferedImage art = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int[] inset = new int[height];
        inset[0] = 2;
        inset[1] = 1;
        inset[height - 2] = 1;
        inset[height - 1] = 2;
        for (int x = 0; x < width; x++) {
            int base = along(colours, width == 1 ? 0 : x / (double) (width - 1));
            for (int y = 0; y < height; y++) {
                if (!inside(inset, width, x, y)) {
                    continue;
                }
                int shade = base;
                if (!inside(inset, width, x, y - 1)) {
                    shade = mix(base, 0xFFFFFF, 0.3);
                } else if (!inside(inset, width, x, y + 1)) {
                    shade = mix(base, 0x000000, 0.4);
                } else if (!inside(inset, width, x, y + 2)) {
                    shade = mix(base, 0x000000, 0.22);
                }
                art.setRGB(x, y, 0xFF000000 | shade);
            }
        }
        Set<Long> letters = new HashSet<>();
        int pen = PAD;
        for (int i = 0; i < label.length(); i++) {
            String[] rows = FONT.get(label.charAt(i));
            for (int row = 0; row < rows.length; row++) {
                for (int col = 0; col < rows[row].length(); col++) {
                    if (rows[row].charAt(col) == '1') {
                        for (int dx = 0; dx < 3; dx++) {
                            for (int dy = 0; dy < 2; dy++) {
                                letters.add(key(pen + 2 * col + dx, top + 2 * row + dy));
                            }
                        }
                    }
                }
            }
            pen += advance(label.charAt(i)) + 2;
        }
        for (long at : letters) {
            int x = (int) (at >> 32) + 1;
            int y = (int) at + 1;
            if (!letters.contains(key(x, y)) && inside(inset, width, x, y)) {
                int base = along(colours, x / (double) (width - 1));
                art.setRGB(x, y, 0xFF000000 | mix(base, 0x000000, luminance(base) > 150 ? 0.45 : 0.32));
            }
        }
        for (long at : letters) {
            int x = (int) (at >> 32);
            int y = (int) at;
            if (x >= 0 && x < width && y >= 0 && y < height) {
                art.setRGB(x, y, 0xFFFFFFFF);
            }
        }
        return art;
    }

    private static long key(int x, int y) {
        return ((long) x << 32) | (y & 0xFFFFFFFFL);
    }

    private static boolean inside(int[] inset, int width, int x, int y) {
        return y >= 0 && y < inset.length && x >= inset[y] && x < width - inset[y];
    }

    /** The gradient at {@code k} from 0 to 1. */
    private static int along(List<TextColor> colours, double k) {
        if (colours.size() == 1) {
            return colours.getFirst().value();
        }
        double at = Math.clamp(k, 0, 1) * (colours.size() - 1);
        int i = Math.min((int) at, colours.size() - 2);
        return mix(colours.get(i).value(), colours.get(i + 1).value(), at - i);
    }

    private static int mix(int a, int b, double k) {
        int r = (int) (((a >> 16) & 0xFF) * (1 - k) + ((b >> 16) & 0xFF) * k);
        int g = (int) (((a >> 8) & 0xFF) * (1 - k) + ((b >> 8) & 0xFF) * k);
        int bl = (int) ((a & 0xFF) * (1 - k) + (b & 0xFF) * k);
        return (r << 16) | (g << 8) | bl;
    }

    private static double luminance(int rgb) {
        return 0.2126 * ((rgb >> 16) & 0xFF) + 0.7152 * ((rgb >> 8) & 0xFF) + 0.0722 * (rgb & 0xFF);
    }
}
