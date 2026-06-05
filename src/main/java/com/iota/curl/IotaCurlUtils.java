package com.iota.curl;

import java.math.BigInteger;
import java.nio.ByteBuffer;

/**
 * Utility functions.
 * Refactored to a separate class for a better readibility.
 *
 * gianluigi.davassi on 13.10.16.
 */
public class IotaCurlUtils {

    public static final int[][] IOTACURL_TRYTE2TRITS_TBL = {
            { 0,  0,  0}, { 1,  0,  0}, {-1,  1,  0},
            { 0,  1,  0}, { 1,  1,  0}, {-1, -1,  1},
            { 0, -1,  1}, { 1, -1,  1}, {-1,  0,  1},
            { 0,  0,  1}, { 1,  0,  1}, {-1,  1,  1},
            { 0,  1,  1}, { 1,  1,  1}, {-1, -1, -1},
            { 0, -1, -1}, { 1, -1, -1}, {-1,  0, -1},
            { 0,  0, -1}, { 1,  0, -1}, {-1,  1, -1},
            { 0,  1, -1}, { 1,  1, -1}, {-1, -1,  0},
            { 0, -1,  0}, { 1, -1,  0}, {-1,  0,  0},
    };

    public static final int[] TRUTH_TABLE = {1, 0, -1, 0, 1, -1, 0, 0, -1, 1, 0};

    // The tryte alphabet: '9' encodes 0, 'A'..'Z' encode 1..26.
    public static final String TRYTE_ALPHABET = "9ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    /** True if the character belongs to the tryte alphabet [9A-Z]. */
    public static boolean isTryte(final char c) {
        return c == '9' || (c >= 'A' && c <= 'Z');
    }

    /** True if {@code s} is non-null and every character is a tryte. */
    public static boolean isTrytes(final String s) {
        if (s == null) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!isTryte(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Validates a trytes input at a system boundary.
     *
     * @throws IllegalArgumentException if {@code value} is null, of the wrong
     *         length, or contains a character outside the tryte alphabet.
     */
    public static void requireTrytes(final String value, final int expectedLength, final String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        if (value.length() != expectedLength) {
            throw new IllegalArgumentException(
                    name + " must be " + expectedLength + " trytes long but was " + value.length());
        }
        if (!isTrytes(value)) {
            throw new IllegalArgumentException(name + " must contain only the tryte alphabet [9A-Z]");
        }
    }

    /**
     * Convert trytes into trits.
     *
     * @param trits Output.
     * @param trytes Input.
     * @param len The number of trytes to load.
     */
    public static void iotaCurlTrytes2Trits(int [] trits, final int offset, final char[] trytes, final int len) {
        for(int i=0; i<len; i++) {
            final int idx = (trytes[i+offset]=='9' ? 0 : trytes[i+offset]-'A'+1);
            trits[3*i+0] = IOTACURL_TRYTE2TRITS_TBL[idx][0];
            trits[3*i+1] = IOTACURL_TRYTE2TRITS_TBL[idx][1];
            trits[3*i+2] = IOTACURL_TRYTE2TRITS_TBL[idx][2];
        }
    }

    /**
     *
     * Convert trits into trytes.
     *
     * @param trytes
     * @param trits
     * @param len
     */
    public static void iotaCurlTrits2Trytes(char [] trytes, final int offset, final int[] trits, final int len) {
        for(int i=0; i<len; i+=3) {
            int j = trits[i];
            if(i+1 < len) {
                j += 3 * trits[i+1];
            }
            if(i+2 < len) {
                j += 9 * trits[i+2];
            }
            if(j < 0) {
                j += 27;
            }
            trytes[i/3+offset] = TRYTE_ALPHABET.charAt(j);
        }
    }

    public static long iotaCurlTritsAdd(final int[] trits, final int len, long n) {
        for(int i=0; i<len; i++) {
            trits[i] += (n % 3);
            n /= 3;
            if(trits[i] > 1) {
                trits[i] -= 3;
                n += 1;
            }
        }
        return n;
    }

    public static void iotaCurlTritsIncrement(int[] trits, int len) {
        // Balanced-ternary increment by one. The bound check must come first:
        // evaluating ++trits[i] before (i < len) would read trits[len] on a
        // full carry (all trits saturated) and throw ArrayIndexOutOfBounds.
        for(int i=0; i<len && ++trits[i]>1; i++) {
            trits[i]=-1;
        }
    }

    // strict min
    // Math.min is (a <= b) ? a : b;
    public static int smin(int a, int b) {
        return (a < b) ? a : b;
    }

    /*
     * That's the only way to convert to a BigInteger an unsigned literal long.
     * Java does not have unsigned long, therefore any literal representation is signed.
     *
     * for instance, 0xffffffffffffffffL is -1 and not 18446744073709551615 (twice the signed Long.MAX_VALUE)
     */
    @Deprecated
    public static BigInteger literalUnsignedLong(final long input) {
        return new BigInteger(1, ByteBuffer.allocate(8).putLong(input).array());
    }

    private static final int not(int a) {
        return a^0b1;
    }

    //https://s16.postimg.org/dthtvw3id/binary_curl.png
    private static int rt(int a, int b, int c, int d) {
        return ((a ^ d) & (not(b) | c));
    }

    private static int lt(int a, int b, int c, int d) {
        return ((b ^ c) | (rt(a,b,c,d)));
    }

    public static int binaryTruth(int a, int b, int c, int d) {
        int x1 = not(rt(a,b,c,d));
        int x2 = lt(a,b,c,d);

        if (x1 == 1 && x2 == 0) {
            return -1;
        }
        if (x1 == 1 && x2 == 1) {
            return 0;
        }
        return 1;
    }

    public static int invokeBinaryTruthOn(int x1, int x2) {
        int a,b,c,d;
        switch (x1) { // switch is direct O(1)
            case 0: {a=1; b=1;} break;
            case 1: {a=0; b=1;} break;
            default: {a=1; b=0;}
        }
        switch (x2) {
            case 0: {c=1; d=1;} break;
            case 1: {c=0; d=1;} break;
            default: {c=1; d=0;}
        }

        return IotaCurlUtils.binaryTruth(a,b,c,d);
    }


}
