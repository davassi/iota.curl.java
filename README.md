# JOTA-CURL

A clean-room **Java port of IOTA's Curl-P-27 hash function and Proof-of-Work miner**.

Curl is the trinary cryptographic primitive at the heart of the legacy IOTA
protocol. It is a [sponge function](https://en.wikipedia.org/wiki/Sponge_function)
— the same construction family as SHA-3 / Keccak — but it operates on
**balanced ternary** rather than binary. This repository provides a faithful,
dependency-free implementation of both the hash and the bit-sliced PoW search.

> **Status:** reference / educational implementation. It mirrors the original C
> reference behaviour and is verified against known hash and mining vectors (see
> [`src/test`](src/test/java/com/iota/curl)). It is not intended as a hardened,
> audited production crypto library.

---

## Table of contents

- [Concepts](#concepts)
- [Features](#features)
- [Requirements](#requirements)
- [Build & test](#build--test)
- [Usage](#usage)
  - [Hashing (library)](#hashing-library)
  - [Proof of Work (library)](#proof-of-work-library)
  - [Command line](#command-line)
- [How it works](#how-it-works)
- [Transaction layout](#transaction-layout)
- [Project structure](#project-structure)
- [References](#references)
- [License](#license)

---

## Concepts

If you have never worked with IOTA's ternary model, three terms are enough to
read the rest of this document:

| Term | Meaning |
| --- | --- |
| **trit** | A *trinary digit* with value `-1`, `0`, or `+1` (balanced ternary). |
| **tryte** | A group of **3 trits**, encoding one of 27 values. Rendered as a character from the alphabet `9ABCDEFGHIJKLMNOPQRSTUVWXYZ`, where `9 = 0` and `A…Z = 1…26`. |
| **MWM** (`minWeightMagnitude`) | The PoW *difficulty*: how many trailing trits of the hash must be `0`. Higher MWM ⇒ exponentially more work. The IOTA reference default is **13**. |

Key fixed sizes used throughout the code:

- A Curl **hash** is `81` trytes = `243` trits (`IOTACURL_HASH_SZ`).
- The Curl **state** is `729` trits — three times the hash width (`IOTACURL_3STATE`).
- A full IOTA **transaction** is `2673` trytes (`TX_LENGTH`).

---

## Features

- ✅ **Curl-P-27 hash** — absorb / transform / squeeze sponge over balanced ternary.
- ✅ **Proof-of-Work miner** — searches for a nonce whose hash meets the requested MWM.
- ⚡ **Bit-sliced transform** — packs **32 nonce candidates per `long`** and tests them
  in a single transform pass via SIMD-within-a-register logic.
- 🧵 **Single- and multi-threaded mining** — the multi-threaded search is
  **deterministic**: it returns the *smallest* valid nonce, identical to the
  single-threaded result, regardless of thread count.
- 🛡️ **Boundary validation** — inputs are checked against the tryte alphabet and
  expected length, failing fast with `IllegalArgumentException` instead of
  producing garbage or `ArrayIndexOutOfBounds`.
- 📦 **Zero runtime dependencies** — only JUnit, and only for the test scope.

---

## Requirements

- **Java 8** (the project targets `1.8`).
- **Maven 3.x** to build and run the test suite.

No third-party runtime dependencies are required.

---

## Build & test

```bash
# Compile
mvn clean compile

# Run the test suite (hash & mining characterization vectors)
mvn test

# Produce the artifact in target/
mvn package
```

---

## Usage

### Hashing (library)

`IotaCurlHash.iotaCurlHash(...)` is a **static, thread-safe** entry point. Each
call builds its own internal state, so concurrent callers do not interfere.

```java
import com.iota.curl.IotaCurlHash;

// `tx` is a tryte string ([9A-Z]); `len` is how many trytes to absorb.
String tx   = "...";              // e.g. a 2673-tryte transaction
String hash = IotaCurlHash.iotaCurlHash(tx, tx.length());

System.out.println("hash: " + hash); // 81 trytes
```

> ⚠️ An *instance* of `IotaCurlHash` carries mutable state and is **not**
> thread-safe. Always go through the static `iotaCurlHash(...)` method for
> concurrent use.

### Proof of Work (library)

```java
import com.iota.curl.IotaCurlMiner;

final IotaCurlMiner miner = new IotaCurlMiner();

// `in` must be exactly IotaCurlMiner.TX_LENGTH (2673) trytes of [9A-Z].
final int minWeightMagnitude = 13;

// Multi-threaded (recommended): deterministic smallest-nonce search.
String mined = miner.iotaCurlProofOfWork(in, minWeightMagnitude);

// Single-threaded equivalent (same result, one thread):
String minedSingle = miner.doCurlPowSingleThread(in, minWeightMagnitude);

System.out.println("mined tx: " + mined);
```

`iotaCurlProofOfWork` wraps the multi-threaded search and converts the checked
concurrency exceptions into an unchecked `IllegalStateException` (preserving the
cause and restoring the interrupt flag), so callers do not have to handle
`ExecutionException` / `InterruptedException` directly.

### Command line

The [`Miner`](src/main/java/com/iota/curl/miner/Miner.java) class is a runnable
demo / CLI.

```bash
# Compile first
mvn clean compile

# Run: java -cp target/classes com.iota.curl.miner.Miner TX_TRYTES [minWeightMagnitude]
java -cp target/classes com.iota.curl.miner.Miner "<2673-tryte transaction>" 13
```

Arguments:

| Argument | Required | Description |
| --- | --- | --- |
| `TX_TRYTES` | yes | The raw transaction, exactly `2673` trytes, characters `[9A-Z]`. |
| `minWeightMagnitude` | no | PoW difficulty; defaults to `13`. Must be `> 0`. |

Running with no arguments prints usage. The tool validates length and alphabet,
mines the transaction, then prints the `approvalNonce`, the full mined
transaction, and the hash of the result.

---

## How it works

**Sponge construction.** Hashing proceeds in the classic sponge phases:

1. **Absorb** — the input trytes are converted to trits and mixed into the
   state `243` trits at a time; after each block the state is run through the
   Curl transform.
2. **Transform** — `27` rounds (hence *Curl-**P-27***). Each round rewrites every
   trit as a Curl S-box of two trits selected by a fixed index permutation that
   splits the state at its midpoint (`PIVOT = 364`). The S-box is a lookup into
   `TRUTH_TABLE` (the boolean derivation of the same table lives in
   `IotaCurlUtils#binaryTruth`, kept as a cross-check, not on the hot path).
3. **Squeeze** — the first `243` trits of the final state are read out as the
   `81`-tryte digest.

**Bit-sliced mining.** The miner runs the *same* permutation as the scalar hash,
but stores trits two-bits-at-a-time inside `long`s. Because a `long` holds
`32 × 2` bits, **32 candidate nonces are evaluated per transform** using the
`lc()` / `ld()` bit-logic helpers and the `LMASK1/2/3` masks. The search:

- precomputes a `midState` from the immutable transaction header once
  (`powInit`),
- then sweeps blocks of 32 nonces, absorbing the trunk and branch hashes and
  checking whether the last `MWM` trits of the result are all `0`.

**Deterministic parallelism.** Worker threads share an atomic block cursor and an
atomic `best` nonce. A worker stops requesting new blocks once the next offset is
`≥ best`, guaranteeing every block that could still hold a *smaller* nonce is
searched. The multi-threaded result is therefore bit-for-bit identical to the
single-threaded smallest-nonce search — see
[`MinerTest`](src/test/java/com/iota/curl/MinerTest.java).

---

## Transaction layout

A `2673`-tryte transaction is processed as a fixed-size header followed by three
`81`-tryte hash fields. The miner only varies the `approvalNonce`:

| Field | Tryte offset | Length (trytes) |
| --- | --- | --- |
| Header (signature, address, value, tag, timestamp, …) | `0` | `2430` |
| `approvalNonce` | `2430` | `81` |
| `trunkTransaction` | `2511` | `81` |
| `branchTransaction` | `2592` | `81` |

---

## Project structure

```
src/
├── main/java/com/iota/curl/
│   ├── IotaCurlHash.java      # Curl-P-27 sponge hash (absorb/transform/squeeze)
│   ├── IotaCurlMiner.java     # Bit-sliced Proof-of-Work search (single & multi-threaded)
│   ├── IotaCurlUtils.java     # Trit/tryte conversion tables, validation, ternary arithmetic
│   └── miner/Miner.java       # Command-line entry point / demo
└── test/java/com/iota/curl/
    ├── HashTest.java          # Hash & conversion vectors, boundary validation
    └── MinerTest.java         # Mining vectors, determinism & parity invariants
```

---

## References

- IOTA — <https://www.iota.org>
- Sponge functions — <https://en.wikipedia.org/wiki/Sponge_function>
- Balanced ternary — <https://en.wikipedia.org/wiki/Balanced_ternary>

---

## License

No license is currently declared for this repository. Until one is added, all
rights are reserved by the author; please contact the maintainer before reusing
the code.

---

*Originally ported by Gianluigi Davassi.*
