// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.tui;

import java.io.IOException;
import java.io.UncheckedIOException;
import org.jline.utils.NonBlockingReader;

/**
 * Single-byte / escape-sequence parser. Reads one logical key from a JLine
 * {@link NonBlockingReader} in raw mode. The 50 ms peek after {@code 0x1B}
 * disambiguates a bare ESC from the start of a CSI sequence.
 */
public final class KeyReader {

    private static final long ESC_PEEK_MS = 50L;

    private KeyReader() {}

    public sealed interface Key
            permits Key.CtrlC,
                    Key.Enter,
                    Key.Space,
                    Key.Tab,
                    Key.Backspace,
                    Key.Escape,
                    Key.Up,
                    Key.Down,
                    Key.Left,
                    Key.Right,
                    Key.Char,
                    Key.Unknown {

        record CtrlC() implements Key {
            public static final CtrlC INSTANCE = new CtrlC();
        }

        record Enter() implements Key {
            public static final Enter INSTANCE = new Enter();
        }

        record Space() implements Key {
            public static final Space INSTANCE = new Space();
        }

        record Tab() implements Key {
            public static final Tab INSTANCE = new Tab();
        }

        record Backspace() implements Key {
            public static final Backspace INSTANCE = new Backspace();
        }

        record Escape() implements Key {
            public static final Escape INSTANCE = new Escape();
        }

        record Up() implements Key {
            public static final Up INSTANCE = new Up();
        }

        record Down() implements Key {
            public static final Down INSTANCE = new Down();
        }

        record Left() implements Key {
            public static final Left INSTANCE = new Left();
        }

        record Right() implements Key {
            public static final Right INSTANCE = new Right();
        }

        record Char(char c) implements Key {}

        record Unknown(int code) implements Key {}
    }

    public static Key read(NonBlockingReader reader) {
        try {
            return dispatch(reader, reader.read());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Like {@link #read(NonBlockingReader)} but returns {@code null} when no
     * byte arrives within {@code timeoutMs}. Lets callers periodically check
     * an external cancellation flag without blocking forever.
     */
    public static Key readOrNull(NonBlockingReader reader, long timeoutMs) {
        try {
            var c = reader.read(timeoutMs);
            if (c == NonBlockingReader.READ_EXPIRED) {
                return null;
            }
            return dispatch(reader, c);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Key dispatch(NonBlockingReader reader, int c) throws IOException {
        return switch (c) {
            case 0x03 -> Key.CtrlC.INSTANCE;
            case 0x0A, 0x0D -> Key.Enter.INSTANCE;
            case 0x09 -> Key.Tab.INSTANCE;
            case 0x20 -> Key.Space.INSTANCE;
            case 0x7F, 0x08 -> Key.Backspace.INSTANCE;
            case 0x1B -> parseEscape(reader);
            default -> {
                if (c >= 0x21 && c <= 0x7E) {
                    yield new Key.Char((char) c);
                }
                yield new Key.Unknown(c);
            }
        };
    }

    private static Key parseEscape(NonBlockingReader reader) throws IOException {
        var peek = reader.read(ESC_PEEK_MS);
        if (peek == NonBlockingReader.READ_EXPIRED || peek < 0) {
            return Key.Escape.INSTANCE;
        }
        if (peek != '[') {
            // Not a CSI; report bare ESC and treat the peeked byte as unknown noise.
            return Key.Escape.INSTANCE;
        }
        var code = reader.read();
        var key =
                switch (code) {
                    case 'A' -> (Key) Key.Up.INSTANCE;
                    case 'B' -> (Key) Key.Down.INSTANCE;
                    case 'C' -> (Key) Key.Right.INSTANCE;
                    case 'D' -> (Key) Key.Left.INSTANCE;
                    default -> (Key) new Key.Unknown(code);
                };
        // Some terminals emit modifier params (e.g. ESC[1;2A). The final letter
        // we already consumed terminates the sequence; trailing params would
        // come BEFORE it, not after. Drain any straggling digits/semicolons/~
        // defensively in case the sequence was a longer form like ESC[1~ that
        // we matched on a digit.
        drainTrailing(reader);
        return key;
    }

    private static void drainTrailing(NonBlockingReader reader) throws IOException {
        while (true) {
            var p = reader.read(1L);
            if (p == NonBlockingReader.READ_EXPIRED || p < 0) {
                return;
            }
            if ((p >= '0' && p <= '9') || p == ';' || p == '~') {
                continue;
            }
            return;
        }
    }
}
