package com.iota.curl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Iota Curl Core mining functions.
 *
 * gianluigi.davassi on 13.10.16.
 */
public class IotaCurlMiner {

    public static final long LMASK1 = (0x5555555555555555l);
    public static final long LMASK2 = (0xAAAAAAAAAAAAAAAAl);
    public static final long LMASK3 = (0xFFFFFFFFFFFFFFFFL);

    static final long[] MAP = {
        (0b11), (0b01), (0b10)
    };

    static final long[] MAP_EX = { LMASK3, LMASK1, LMASK2 };

    // The length of transaction header (before approvalNonce) in trytes.
    public static final int TX_HEADER_SZ = 2430;

    // Full transaction length in trytes: header + approvalNonce + trunk + branch.
    public static final int TX_LENGTH = TX_HEADER_SZ + 3 * IotaCurlHash.IOTACURL_HASH_SZ; // 2673

    private static final int HASH_SIZE = 3*IotaCurlHash.IOTACURL_HASH_SZ;   // 243 trits
    private static final int STATE_SIZE = 3*IotaCurlHash.IOTACURL_STATE_SZ; // 729 trits

    // Curl-P-27: 27 rounds, index permutation split at the state midpoint.
    private static final int NUMBER_OF_ROUNDS = 27;
    private static final int PIVOT = STATE_SIZE / 2; // 364

    // Tryte offsets of the trailing transaction fields within the raw tx.
    private static final int APPROVAL_NONCE_OFFSET = TX_HEADER_SZ;                               // 2430
    private static final int TRUNK_OFFSET = TX_HEADER_SZ + IotaCurlHash.IOTACURL_HASH_SZ;        // 2511
    private static final int BRANCH_OFFSET = TX_HEADER_SZ + 2 * IotaCurlHash.IOTACURL_HASH_SZ;   // 2592

    private final long[] midState = new long[3 * IotaCurlHash.IOTACURL_STATE_SZ];

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

    protected final void doPowAbsorb(final long[] state, final int[] trits) {
        for (int i=0; i<HASH_SIZE; i++) {
            state[i] = MAP_EX[trits[i]+1];
        }
    }

    /**
     * Bit-sliced Curl-P-27 transform: the same permutation as the scalar hash,
     * but each long carries {@value #PARALLEL} trits (2 bits each) processed in
     * parallel via lc()/ld(). The result lands in {@code state} because the
     * round count (27) is odd.
     */
    protected void doPowTransform(final long [] state) {
        long[] state1 = state; // alias: even rounds write here, so the final round does too
        long[] state2 = Arrays.copyOf(state, state.length);

        for(int r=0; r<NUMBER_OF_ROUNDS; r++) {
            {
                final long a = state2[0];
                final long b = state2[PIVOT];
                final long c = lc(a);
                state1[0] = ld(b, c);
            }
            for (int i = 0; i < PIVOT; i++) {
                final long a3 = state2[PIVOT - i - 1];
                final long a1 = state2[PIVOT - i];
                final long a2 = state2[STATE_SIZE - i - 1];
                final long c1 = lc(a1);
                final long c2 = lc(a2);
                state1[2 * i + 1] = ld(a2, c1);
                state1[2 * i + 2] = ld(a3, c2);
            }
            final long[] t = state1;
            state1 = state2;
            state2 = t;
        }
    }

    private long doWork(final int minWeightMagnitude, long offset) {
        final int [] an = Arrays.copyOf(approvalNonce, HASH_SIZE);
        final long [] state = Arrays.copyOf(midState, midState.length);

        IotaCurlUtils.iotaCurlTritsAdd(an, HASH_SIZE, offset);

        // Search. Process approvalNonce.
        for(int i=0; i<PARALLEL; i++) {
            for(int j=0; j<HASH_SIZE; j++) {
                state[j] |= ((MAP[an[j]+1]) << (i*2));
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
                long n = state[j] >> ((2*i)) & (MAP[0]);
                complete = complete && (n == (MAP[1]));
            }
            if(!complete) {
                continue;
            }
            return (offset + i); // If the solution has been found.
        }
        return -1; // Not found. 0 is a valid nonce, so it cannot be the sentinel.
    }

    private final char[] powInit(final String tx) {
        IotaCurlUtils.requireTrytes(tx, TX_LENGTH, "tx");

        final IotaCurlHash ctx = new IotaCurlHash();
        final char[] trx = tx.toCharArray();
        ctx.doAbsorb(trx, TX_HEADER_SZ);

        for (int i = 0; i < STATE_SIZE; i++) {
            midState[i] = (i < HASH_SIZE) ? 0L : (MAP_EX[ctx.getCurlStateValue(i) + 1]);
        }

        IotaCurlUtils.iotaCurlTrytes2Trits(approvalNonce, APPROVAL_NONCE_OFFSET, trx, IotaCurlHash.IOTACURL_HASH_SZ);
        IotaCurlUtils.iotaCurlTrytes2Trits(trunkTransaction, TRUNK_OFFSET, trx, IotaCurlHash.IOTACURL_HASH_SZ);
        IotaCurlUtils.iotaCurlTrytes2Trits(branchTransaction, BRANCH_OFFSET, trx, IotaCurlHash.IOTACURL_HASH_SZ);
        return trx;
    }

    public void powFinalize(char [] txar, long result) {
        IotaCurlUtils.iotaCurlTritsAdd(approvalNonce, HASH_SIZE, result);
        IotaCurlUtils.iotaCurlTrits2Trytes(txar, TX_HEADER_SZ, approvalNonce, HASH_SIZE);
    }

    public String doCurlPowSingleThread(String tx, final int minWeightMagnitude) {
        final char[] trax = powInit(tx);

        long offset = 0L, result;
        while ((result = doWork(minWeightMagnitude, offset)) < 0) {
            offset += PARALLEL;
        }
        powFinalize(trax, result);
        return new String(trax);
    }

    /**
     * Runs the proof of work and returns the mined transaction trytes.
     * Translates the checked concurrency exceptions into an unchecked failure
     * while preserving the cause, and restores the interrupt flag.
     */
    public String iotaCurlProofOfWork(String tx, final int minWeightMagnitude) {
        try {
            return doCurlPowMultiThread(tx, minWeightMagnitude);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Proof of work was interrupted", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Proof of work failed", e.getCause());
        }
    }

    public String doCurlPowMultiThread(String tx, final int minWeightMagnitude) throws ExecutionException, InterruptedException {
        final char[] trax = powInit(tx);

        final int workers = workerCount(Runtime.getRuntime().availableProcessors());
        final ExecutorService executor = Executors.newFixedThreadPool(workers);

        // Shared cursor over nonce blocks plus the smallest valid nonce found so
        // far. Workers stop grabbing blocks once the next offset is >= best, so
        // every block that could still hold a smaller nonce is searched. The
        // result is therefore deterministic and equal to the single-threaded
        // smallest-nonce search.
        final AtomicLong nextOffset = new AtomicLong(0L);
        final AtomicLong best = new AtomicLong(Long.MAX_VALUE);

        try {
            final Collection<Callable<Void>> tasks = new ArrayList<>(workers);
            for (int i = 0; i < workers; i++) {
                tasks.add(() -> {
                    long offset;
                    while ((offset = nextOffset.getAndAdd(PARALLEL)) < best.get()) {
                        final long found = doWork(minWeightMagnitude, offset);
                        if (found >= 0) {
                            long current;
                            do {
                                current = best.get();
                            } while (found < current && !best.compareAndSet(current, found));
                        }
                    }
                    return null;
                });
            }
            // invokeAll blocks until every worker finishes; get() re-throws any
            // exception a worker raised instead of silently swallowing it.
            for (final Future<Void> task : executor.invokeAll(tasks)) {
                task.get();
            }
        } finally {
            executor.shutdown();
        }

        final long result = best.get();
        if (result == Long.MAX_VALUE) {
            throw new IllegalStateException("Proof of work found no valid nonce");
        }
        powFinalize(trax, result);
        return new String(trax);
    }

    /**
     * Number of worker threads: one per CPU leaving one for the caller, but
     * always at least one so single-core machines still run.
     */
    static int workerCount(final int availableProcessors) {
        return Math.max(1, availableProcessors - 1);
    }

}