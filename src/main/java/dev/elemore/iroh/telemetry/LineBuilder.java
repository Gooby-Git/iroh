package dev.elemore.iroh.telemetry;

/**
 * Reusable text buffer with allocation-free number formatting (card TEL-1). {@link #toString} allocates the String and is
 * only called when a displayed value is rebuilt (the overlay's sample rate), never per frame.
 */
public final class LineBuilder {
    private final char[] buf;
    private int len;

    public LineBuilder(int capacity) { this.buf = new char[capacity]; }

    public LineBuilder clear() { len = 0; return this; }

    public int length() { return len; }

    public LineBuilder s(String lit) {
        for (int i = 0, n = lit.length(); i < n && len < buf.length; i++) buf[len++] = lit.charAt(i);
        return this;
    }

    public LineBuilder c(char ch) {
        if (len < buf.length) buf[len++] = ch;
        return this;
    }

    public LineBuilder i(long v) {
        if (v == Long.MIN_VALUE) return s("-9223372036854775808");
        if (v < 0) { c('-'); v = -v; }
        int digits = 1;
        for (long t = v; t >= 10; t /= 10) digits++;
        if (len + digits > buf.length) return this;
        int p = len + digits;
        do { buf[--p] = (char) ('0' + (int) (v % 10)); v /= 10; } while (v != 0);
        len += digits;
        return this;
    }

    /** {@code decimals} (0..3) digits after the point, rounded half up; non-finite prints "-". */
    public LineBuilder f(double v, int decimals) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return c('-');
        if (v < 0) { c('-'); v = -v; }
        long scale = decimals == 0 ? 1 : decimals == 1 ? 10 : decimals == 2 ? 100 : 1000;
        long scaled = (long) (v * scale + 0.5);
        i(scaled / scale);
        if (decimals > 0) {
            c('.');
            long frac = scaled % scale;
            for (long d = scale / 10; d > 0; d /= 10) c((char) ('0' + (int) (frac / d % 10)));
        }
        return this;
    }

    @Override public String toString() { return new String(buf, 0, len); }

    /** Content equals {@code s} (test helper, allocation-free). */
    public boolean contentEquals(String s) {
        if (s.length() != len) return false;
        for (int i = 0; i < len; i++) if (buf[i] != s.charAt(i)) return false;
        return true;
    }

    public char charAt(int i) { return buf[i]; }
}
