package com.routiqo.core.publiclive.privacy;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Internal, unwired sparse thresholded histogram for a fixed 30-day public key universe.
 *
 * <p>Each verified person contributes to at most one key in the entire pilot horizon.
 * A key is a precommitted anchor × TRAFFIC × value × five-minute window tuple;
 * the public universe contains at most 345,600 keys (10 × 4 × 30 × 24 × 12).
 * The input map enforces one key per supplied person reference for this call, but
 * verification, global contribution bounding and release accounting are external
 * obligations. No publisher, API or flag uses this class.
 *
 * <p>For every <em>observed</em> key, release the key alone when count + N is at least 22,
 * where N=G1-G2 and each G is failures before success from independent fair
 * cryptographic bits, capped at 64 failures. The ideal uncapped law is
 * P(N=z)=2^(-|z|)/3. For a key present in both neighboring
 * datasets, the integer-Laplace likelihood ratio is at most 2 for a count change
 * of one. For add/remove person adjacency, a newly observed singleton key has
 * no output support in the neighboring dataset; its probability of crossing the
 * threshold is exactly P(N≥21)=1/(3·2^20)=1/3,145,728. Therefore a single
 * ideal release is (ε=ln(2), δ=1/3,145,728)-DP under add/remove person adjacency.
 * Coupling each capped draw to its ideal draw changes the full fixed-universe
 * transcript with probability at most K·2^-63 for K public keys. The capped
 * release therefore has δ no greater than 1/3,145,728 + 3K·2^-63, with
 * K at most 345,600. Under replacement
 * adjacency two keys can change, so this stated bound does not apply.
 *
 * <p>Only observed keys are sampled, so a truly zero-support key is never emitted.
 * A singleton can cross the threshold, and a selected key is not a verified road
 * condition or a lower bound on supporters. A data-dependent key universe or a
 * second release over the same people requires new analysis and budget accounting.
 * Two fixed-size random draws are made for every public key, including absent
 * keys. This removes the sampler's observed-key work variation. Histogram
 * construction, database work, failures and publication timing remain potential
 * side channels; any future publisher must handle them and use a fixed public
 * schedule.
 *
 * @see <a href="https://docs.opendp.org/en/stable/api/user-guide/measurements/thresholded-noise-mechanisms.html">OpenDP thresholded noise</a>
 * @see <a href="https://nvlpubs.nist.gov/nistpubs/SpecialPublications/NIST.SP.800-226.pdf">NIST SP 800-226</a>
 */
public final class SparseThresholdDpHistogram {
    public static final int THRESHOLD = 22;
    public static final int MAX_PUBLIC_KEYS = 345_600;
    public static final BigInteger IDEAL_DELTA_DENOMINATOR =
            BigInteger.valueOf(3).shiftLeft(THRESHOLD - 2);
    private static final UUID NIL = new UUID(0, 0);
    private final List<String> publicKeys;
    private final Set<String> allowedKeys;
    private final SecureRandom random;
    private final AtomicBoolean used = new AtomicBoolean();

    public SparseThresholdDpHistogram(List<String> publicKeys) {
        this(publicKeys, new SecureRandom());
    }

    SparseThresholdDpHistogram(List<String> publicKeys, SecureRandom random) {
        Objects.requireNonNull(publicKeys);
        if (publicKeys.isEmpty() || publicKeys.size() > MAX_PUBLIC_KEYS
                || publicKeys.stream().anyMatch(key -> key == null || key.isBlank()
                        || key.length() > 128)
                || Set.copyOf(publicKeys).size() != publicKeys.size())
            throw new IllegalArgumentException("Invalid fixed public key universe");
        this.publicKeys = List.copyOf(publicKeys);
        this.allowedKeys = Set.copyOf(publicKeys);
        this.random = Objects.requireNonNull(random);
    }

    /** One release per instance. A durable global budget is still required before wiring. */
    public Set<String> release(Map<UUID, String> oneKeyPerVerifiedPerson) {
        Objects.requireNonNull(oneKeyPerVerifiedPerson);
        Map<UUID, String> snapshot = Map.copyOf(oneKeyPerVerifiedPerson);
        if (snapshot.containsKey(NIL)
                || snapshot.values().stream().anyMatch(key -> !allowedKeys.contains(key)))
            throw new IllegalArgumentException("Invalid bounded contribution");
        if (!used.compareAndSet(false, true)) throw new IllegalStateException("Already released");
        Map<String, BigInteger> counts = new LinkedHashMap<>();
        for (String key : snapshot.values())
            counts.merge(key, BigInteger.ONE, BigInteger::add);
        Set<String> selected = new LinkedHashSet<>();
        for (String key : publicKeys) {
            BigInteger count = counts.get(key);
            long noise = (long) Long.numberOfTrailingZeros(random.nextLong())
                    - Long.numberOfTrailingZeros(random.nextLong());
            if (count != null && count.add(BigInteger.valueOf(noise))
                    .compareTo(BigInteger.valueOf(THRESHOLD)) >= 0)
                selected.add(key);
        }
        return Collections.unmodifiableSet(selected);
    }
}
