package de.raindancer.core.content.music;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.OptionalDouble;

/**
 * How long an Ogg Vorbis file plays: the last page's granule position (samples) over the sample rate in the
 * Vorbis identification header. Minecraft plays only Vorbis, so nothing else is read.
 */
public final class OggLength {

    private static final byte[] PAGE = "OggS".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] VORBIS = "\u0001vorbis".getBytes(StandardCharsets.ISO_8859_1);

    private OggLength() {
    }

    public static OptionalDouble seconds(byte[] file) {
        int header = find(file, VORBIS, 0);
        if (!startsWith(file, PAGE, 0) || header < 0 || header + 16 > file.length) {
            return OptionalDouble.empty();
        }
        ByteBuffer le = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN);
        long rate = Integer.toUnsignedLong(le.getInt(header + 12));
        for (int at = file.length - PAGE.length; at >= 0; at--) {
            if (startsWith(file, PAGE, at) && at + 14 <= file.length) {
                long samples = le.getLong(at + 6);
                return rate > 0 && samples > 0 ? OptionalDouble.of((double) samples / rate) : OptionalDouble.empty();
            }
        }
        return OptionalDouble.empty();
    }

    private static int find(byte[] in, byte[] what, int from) {
        for (int at = from; at <= in.length - what.length; at++) {
            if (startsWith(in, what, at)) {
                return at;
            }
        }
        return -1;
    }

    private static boolean startsWith(byte[] in, byte[] what, int at) {
        if (at < 0 || at + what.length > in.length) {
            return false;
        }
        for (int i = 0; i < what.length; i++) {
            if (in[at + i] != what[i]) {
                return false;
            }
        }
        return true;
    }
}
