
package com.iota.curl.miner;

import com.iota.curl.IotaCurlHash;
import com.iota.curl.IotaCurlMiner;
import com.iota.curl.IotaCurlUtils;

import java.util.Locale;

/**
 * Miner class.
 *
 * This class is a test case for IotaCurlMiner trinary function lib.
 *
 * Ported by gianluigi.davassi on 13.10.16.
 */
public class Miner {

    public static final int TX_LENGTH = IotaCurlMiner.TX_LENGTH;

    private static final int DEFAULT_MIN_WEIGHT_MAGNITUDE = 13;

    public static void main(final String ... args) {

        if (args == null || args.length < 1) {
            stderr("usage: TX_TRYTES [minWeightMagnitude=%d]\n", DEFAULT_MIN_WEIGHT_MAGNITUDE);
            stderr("TX_TRYTES:\n" +
                    "\tThe raw transaction data expressed in trytes-encoded string.\n" +
                    "\tShould be " + TX_LENGTH + " letters long and must only include letters [9A-Z].\n" +
                    "minWeightMagnitude:\n\tThe difficulty factor of Curl hash function.\n" +
                    "\tCurrently the default value is set to " + DEFAULT_MIN_WEIGHT_MAGNITUDE +
                    " as in the reference implementation.\n");
            return;
        }

        final String tx = args[0];
        if (tx.length() != TX_LENGTH) {
            stderr("The length of TX_TRYTES is not correct (expected %d, got %d).", TX_LENGTH, tx.length());
            return;
        }

        if (!IotaCurlUtils.isTrytes(tx)) {
            stderr("The raw transaction data must contain only the tryte alphabet [9A-Z].");
            return;
        }

        // Load the minWeightMagnitude (optional second argument).
        int minWeightMagnitude = DEFAULT_MIN_WEIGHT_MAGNITUDE;
        if (args.length > 1) {
            try {
                minWeightMagnitude = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                stderr("minWeightMagnitude must be an integer but was '%s'.", args[1]);
                return;
            }
        }
        if (minWeightMagnitude <= 0) {
            stderr("minWeightMagnitude (%d) should be greater than zero.", minWeightMagnitude);
            return;
        }

        // Show the data to mine.
        stdout("I: mining for:\nTX_TRYTES: %s\nminWeightMagnitude: %d", tx, minWeightMagnitude);

        // Do PoW. Keep the mined transaction; the input tx is left untouched.
        stdout("I: mining... ");

        final IotaCurlMiner iotacurl = new IotaCurlMiner();
        final String mined = iotacurl.iotaCurlProofOfWork(tx, minWeightMagnitude);
        stdout("done.");

        // Print the approvalNonce and the final (mined) transaction.
        final String approvalNonce = mined.substring(
                IotaCurlMiner.TX_HEADER_SZ, IotaCurlMiner.TX_HEADER_SZ + IotaCurlHash.IOTACURL_HASH_SZ);
        stdout("approvalNonce: %s", approvalNonce);
        stdout("txTrytes: %s", mined);

        // Print hash of the mined transaction.
        final String hash = IotaCurlHash.iotaCurlHash(mined, TX_LENGTH);
        stdout("hash: %s", hash);
    }

    private static final void stdout(String str, Object ... args) {
        final String formattedStr = String.format(Locale.ENGLISH, str, args);
        System.out.println(formattedStr);
    }

    private static final void stderr(String str, Object ... args) {
        final String formattedStr = String.format(Locale.ENGLISH, str, args);
        System.err.println("Error: " + formattedStr);
    }
}
