
package com.iota.curl;

import java.util.Arrays;

/**
 * {@link IotaCurlHash} class definition.
 *
 * This class has an internal state, therefore it has not been designed to be
 * thread-safe. You must access it statically, the main method is
 *
 * IotaCurlMiner.iotaCurlHash(tx,len); // thread safe
 *
 * The hash core functionalities have been ported in this class,
 * utility functions and tables on IotaCurlUtils
 *
 * gianluigi.davassi on 13.10.16.
 */
public class IotaCurlHash {

    // Hash output length in trytes (equivalently 243 trits).
    public static final int IOTACURL_HASH_SZ = 81;

    // Hash output length in trits (3 trits per tryte); also the sponge rate.
    public static final int IOTACURL_STATE_SZ = (3 * IOTACURL_HASH_SZ); // 243

    // Full Curl state length in trits.
    public static final int IOTACURL_3STATE = (3 * IOTACURL_STATE_SZ); // 729

    // Curl-P-27 applies 27 transform rounds.
    private static final int NUMBER_OF_ROUNDS = 27;

    // The Curl index permutation splits the state at its midpoint.
    private static final int PIVOT = IOTACURL_3STATE / 2; // 364

    private static final class CurlState {
        int[] state = new int[IOTACURL_3STATE];
    };

    private final CurlState curlState = new CurlState();

    public static String iotaCurlHash(final String tx, final int len) {
        if (tx == null) {
            throw new IllegalArgumentException("tx must not be null");
        }
        if (len < 0 || len > tx.length()) {
            throw new IllegalArgumentException("len must be in [0, " + tx.length() + "] but was " + len);
        }
        for (int i = 0; i < len; i++) {
            if (!IotaCurlUtils.isTryte(tx.charAt(i))) {
                throw new IllegalArgumentException("tx must contain only the tryte alphabet [9A-Z]");
            }
        }
        final IotaCurlHash ctx = new IotaCurlHash();
        ctx.doAbsorb(tx.toCharArray(), len);
        return ctx.doFinalize();
    }

    /**
     * Absorb given trytes.
     */
    protected void doAbsorb(final char [] trytes, final int len) {
        for(int i=0; i<len; i+=IOTACURL_HASH_SZ) {
            IotaCurlUtils.iotaCurlTrytes2Trits(curlState.state, i, trytes, IotaCurlUtils.smin(len-i, IOTACURL_HASH_SZ));
            doHashTransform(curlState.state);
        }
    }

    protected void doHashTransform(int [] state1) {
        int [] state2 = Arrays.copyOf(state1, state1.length);

        // Each round rewrites every state trit as the Curl S-box of two trits
        // chosen by a fixed index permutation. TRUTH_TABLE is indexed by
        // a + 4*b + 5 with a, b in {-1, 0, 1}. The loop is unrolled by two:
        // one iteration produces the trits at positions 2*i+1 and 2*i+2.
        for(int r=0; r<NUMBER_OF_ROUNDS; r++) {
            state1[0] = IotaCurlUtils.TRUTH_TABLE[state2[0] + (state2[PIVOT] << 2) + 5];

            for(int i=0; i<PIVOT; i++) {
                state1[2*i+1] = IotaCurlUtils.TRUTH_TABLE[state2[PIVOT-i] + (state2[IOTACURL_3STATE-(i+1)] << 2) + 5];
                state1[2*i+2] = IotaCurlUtils.TRUTH_TABLE[state2[IOTACURL_3STATE-(i+1)] + (state2[PIVOT-(i+1)] << 2) + 5];
            }
            // The result of this round lives in state1; swap so the next round
            // reads it. After 27 (odd) rounds the result is back in the array
            // the caller passed in.
            int [] t = state1;
            state1 = state2;
            state2 = t;
        }
    }

    private String doFinalize() {
        // Squeeze: the first IOTACURL_STATE_SZ trits become IOTACURL_HASH_SZ trytes.
        final char [] hash = new char[IOTACURL_HASH_SZ];
        IotaCurlUtils.iotaCurlTrits2Trytes(hash, 0, curlState.state, IOTACURL_STATE_SZ);
        return new String(hash);
    }

    public int getCurlStateValue(int index) {
        return curlState.state[index];
    }
}
