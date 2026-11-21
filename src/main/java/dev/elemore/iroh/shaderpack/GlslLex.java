package dev.elemore.iroh.shaderpack;

import java.util.ArrayList;
import java.util.List;

/** GLSL tokens with positions and 1-based line numbers, for the pack translator (input has no comments). */
final class GlslLex {

    enum T { IDENT, NUMBER, PUNCT, PREPROC }

    record Tok(T kind, String text, int start, int end, int line) {
        boolean is(String s) {
            return text.equals(s);
        }
    }

    private GlslLex() {}

    /** Block comments become spaces with their newlines kept, line comments spaces. Offsets survive. */
    static String stripComments(String s) {
        StringBuilder out = new StringBuilder(s.length());
        int n = s.length();
        int i = 0;
        while (i < n) {
            char c = s.charAt(i);
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '/') {
                while (i < n && s.charAt(i) != '\n') {
                    out.append(' ');
                    i++;
                }
            } else if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                out.append("  ");
                i += 2;
                while (i < n && !(s.charAt(i) == '*' && i + 1 < n && s.charAt(i + 1) == '/')) {
                    out.append(s.charAt(i) == '\n' ? '\n' : ' ');
                    i++;
                }
                if (i < n) {
                    out.append("  ");
                    i += 2;
                }
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    static List<Tok> lex(String s) {
        List<Tok> toks = new ArrayList<>();
        int n = s.length();
        int i = 0;
        int line = 1;
        boolean lineStart = true;
        while (i < n) {
            char c = s.charAt(i);
            if (c == '\n') {
                line++;
                lineStart = true;
                i++;
                continue;
            }
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            int st = i;
            if (c == '#' && lineStart) {
                while (i < n && s.charAt(i) != '\n')
                    i++;
                toks.add(new Tok(T.PREPROC, s.substring(st, i), st, i, line));
                continue;
            }
            lineStart = false;
            if (Character.isLetter(c) || c == '_') {
                while (i < n && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_'))
                    i++;
                toks.add(new Tok(T.IDENT, s.substring(st, i), st, i, line));
            } else if (Character.isDigit(c) || (c == '.' && i + 1 < n && Character.isDigit(s.charAt(i + 1)))) {
                i++;
                while (i < n) {
                    char d = s.charAt(i);
                    if ((d == '+' || d == '-') && (s.charAt(i - 1) == 'e' || s.charAt(i - 1) == 'E')
                            && !(s.startsWith("0x", st) || s.startsWith("0X", st))) {
                        i++;
                    } else if (Character.isLetterOrDigit(d) || d == '.' || d == '_') {
                        i++;
                    } else {
                        break;
                    }
                }
                toks.add(new Tok(T.NUMBER, s.substring(st, i), st, i, line));
            } else {
                i++;
                toks.add(new Tok(T.PUNCT, String.valueOf(c), st, i, line));
            }
        }
        return toks;
    }
}
