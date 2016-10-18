package com.iota.curl;

import java.math.BigInteger;
import java.util.Arrays;

/**
 * Iota Curl Core mining functions.
 *
 * gianluigi.davassi on 13.10.16.
 */
public class IotaCurlMiner {

    public static final long LMASK1 = (0x5555555555555555l);
    public static final long LMASK2 = (0xAAAAAAAAAAAAAAAAl);
    public static final long LMASK3 = (0xFFFFFFFFFFFFFFFFL);

    public static final BigInteger MASK1 = IotaCurlUtils.literalUnsignedLong(LMASK1);
    public static final BigInteger MASK2 = IotaCurlUtils.literalUnsignedLong(LMASK2);
    public static final BigInteger MASK3 = IotaCurlUtils.literalUnsignedLong(LMASK3);

    static final BigInteger[] MAP = {
        BigInteger.valueOf(0b11),
        BigInteger.valueOf(0b01),
        BigInteger.valueOf(0b10)
    };

    static final BigInteger[] MAP_EX = { MASK3, MASK1, MASK2 };

    // The length of transaction header (before approvalNonce) in trytes.
    public static final int TX_HEADER_SZ = 2430;

    private static final int HASH_SIZE = 3*IotaCurlHash.IOTACURL_HASH_SZ;

    private final BigInteger[] midState = createMidStateArray();

    private static BigInteger[] createMidStateArray() {
        BigInteger[] bstat = new BigInteger[3 * IotaCurlHash.IOTACURL_STATE_SZ];
        Arrays.fill(bstat, BigInteger.ZERO);
        return bstat;
    }

    private final int[] approvalNonce = new int[HASH_SIZE];
    private final int[] trunkTransaction = new int[HASH_SIZE];
    private final int[] branchTransaction = new int[HASH_SIZE];

    private final int PARALLEL = 32;

    protected static long lc(long a) {
        return ((((a) ^ ((a)>>1)) & LMASK1) | (((a)<<1) & LMASK2));
    }

    protected static long ld(long b, long c) {
        return (c) ^ (LMASK2 & (b) & (((b) & (c))<<1)) ^ (LMASK1 & ~(b) & (((b) & (c))>>1));
    }

    protected static BigInteger c(final BigInteger a) {
        return IotaCurlUtils.literalUnsignedLong(lc(a.longValue()));
    }

    protected static BigInteger d(final BigInteger b, final BigInteger c) {
        return IotaCurlUtils.literalUnsignedLong(ld(b.longValue(), c.longValue()));
    }

    protected final void doPowAbsorb(final BigInteger[] state, final int[] trits) {
        for (int i=0; i<HASH_SIZE; i++) {
            state[i] = MAP_EX[trits[i]+1];
        }
    }

    protected void doPowTransform(final BigInteger [] state) {
        BigInteger[] state1 = state; // shallow copy
        BigInteger[] state2 = Arrays.copyOf(state, state.length);

        for(int r=0; r<27; r++) {
            {
                final BigInteger a = state2[0];
                final BigInteger b = state2[364];
                final BigInteger c = c(a);
                state1[0] = d(b, c);
            }
            for (int i = 0; i < (3*IotaCurlHash.IOTACURL_STATE_SZ / 2); i++) {
                final BigInteger a3 = state2[364 - i - 1];
                final BigInteger a1 = state2[364 - i];
                final BigInteger a2 = state2[729 - i - 1];
                final BigInteger c1 = c(a1);
                final BigInteger c2 = c(a2);
                state1[2 * i + 1] = d(a2, c1);
                state1[2 * i + 2] = d(a3, c2);
            }
            final BigInteger[] t = state1;
            state1 = state2;
            state2 = t;
        }
    }

    private long doWork(final int minWeightMagnitude, long offset) {
        final int [] an = Arrays.copyOf(approvalNonce, HASH_SIZE);
        BigInteger [] state = Arrays.copyOf(midState, 3*IotaCurlHash.IOTACURL_STATE_SZ);

        IotaCurlUtils.iotaCurlTritsAdd(an, HASH_SIZE, offset);

        // Search. Process approvalNonce.
        for(int i=0; i<PARALLEL; i++) {
            for(int j=0; j<HASH_SIZE; j++) {
                state[j] = state[j].or((MAP[an[j]+1]).shiftLeft(i*2));
            }
            IotaCurlUtils.iotaCurlTritsIncrement(an, HASH_SIZE);
        }

        doPowTransform(state);

        // Process trunkTransaction/branchTransaction.
        doPowAbsorb(state, trunkTransaction);
        doPowTransform(state);

        doPowAbsorb(state, branchTransaction);
        doPowTransform(state);

        // Check if work is done.
        for(int i=0; i<PARALLEL; i++) {

            boolean complete = true;

            for(int j=HASH_SIZE-minWeightMagnitude; j<HASH_SIZE; j++) {
                BigInteger n = state[j].shiftRight((2*i)).and(MAP[0]);
                complete = complete && (n.equals(MAP[1]));
            }
            if(!complete) {
                continue;
            }
            return (offset + i); // If the solution has been found.
        }
        return 0;
    }

    private final char[] powInit(final String tx) {

        final IotaCurlHash ctx = new IotaCurlHash();
        final char[] trx = tx.toCharArray();
        ctx.doAbsorb(trx, TX_HEADER_SZ);

        for (int i = 0; i < 3*IotaCurlHash.IOTACURL_STATE_SZ; i++) {
            midState[i] = (i < HASH_SIZE) ? BigInteger.ZERO
                    : MAP_EX[ctx.getCurlStateValue(i) + 1];
        }

        IotaCurlUtils.iotaCurlTrytes2Trits(approvalNonce, 7290 / 3, trx, IotaCurlHash.IOTACURL_HASH_SZ);
        IotaCurlUtils.iotaCurlTrytes2Trits(trunkTransaction, 7533 / 3, trx, IotaCurlHash.IOTACURL_HASH_SZ);
        IotaCurlUtils.iotaCurlTrytes2Trits(branchTransaction, 7776 / 3, trx, IotaCurlHash.IOTACURL_HASH_SZ);
        return trx;
    }

    public void powFinalize(char [] txar, long result) {
        IotaCurlUtils.iotaCurlTritsAdd(approvalNonce, HASH_SIZE, result);
        IotaCurlUtils.iotaCurlTrits2Trytes(txar, TX_HEADER_SZ, approvalNonce, HASH_SIZE);
    }

    public String doCurlPowSingleThread(String tx, final int minWeightMagnitude) {
        final char[] trax = powInit(tx);

        long offset = 0L, result;
        while ((result = doWork(minWeightMagnitude, offset)) == 0) {
            offset += PARALLEL;
        }
        powFinalize(trax, result);
        return new String(trax);
    }

    public String iotaCurlProofOfWork(String tx, final int minWeightMagnitude) {
        return doCurlPowSingleThread(tx, minWeightMagnitude);
    }

    public String doCurlPowMultiThread(String tx, final int minWeightMagnitude) {
        // still left TODO
        return null;
    }

}