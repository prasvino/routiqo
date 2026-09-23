package com.routiqo.core.publiclive.privacy;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DpHistogramTest {
    @Test void sparseMechanismSamplesObservedKeysOnlyAndThresholdsNoisyValue() {
        // 1 + (21 - 0) reaches threshold 22; absent key b also draws noise.
        var random = new ScriptedRandom(1L << 21, 1L, 1L, 1L);
        var mechanism = new SparseThresholdDpHistogram(List.of("a", "b"), random);
        var selected = mechanism.release(Map.of(UUID.randomUUID(), "a"));
        assertThat(selected).containsExactly("a");
        assertThat(random.remaining()).isZero();
        assertThatThrownBy(() -> mechanism.release(Map.of()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> selected.add("b"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void sparseNeverEmitsZeroSupportKeyEvenWithNoisyHighCount() {
        var mechanism = new SparseThresholdDpHistogram(List.of("a", "b"),
                new ScriptedRandom(0L, 1L, 0L, 1L));
        assertThat(mechanism.release(Map.of())).isEmpty();
    }

    @Test void sparseRejectsJustBelowThresholdAndNegativeNoise() {
        // 1 + 20 - 0 = 21, below the inclusive threshold.
        var belowRandom = new ScriptedRandom(1L << 20, 1L);
        assertThat(new SparseThresholdDpHistogram(List.of("a"), belowRandom)
                .release(Map.of(UUID.randomUUID(), "a"))).isEmpty();
        assertThat(belowRandom.remaining()).isZero();

        // 1 + 0 - 1 = 0: the second geometric draw reduces the count.
        var negativeRandom = new ScriptedRandom(1L, 2L);
        assertThat(new SparseThresholdDpHistogram(List.of("a"), negativeRandom)
                .release(Map.of(UUID.randomUUID(), "a"))).isEmpty();
        assertThat(negativeRandom.remaining()).isZero();
    }

    @Test void exactTailAndPrivacyCostMatchSymbolicProof() {
        // P(N >= t) = (2/3) * 2^-t for t >= 0. For singleton count 1 and
        // inclusive threshold 22, t=21, giving 1 / (3 * 2^20).
        assertThat(SparseThresholdDpHistogram.THRESHOLD).isEqualTo(22);
        assertThat(SparseThresholdDpHistogram.IDEAL_DELTA_DENOMINATOR)
                .isEqualTo(BigInteger.valueOf(3).multiply(BigInteger.TWO.pow(20)));
        assertThat(3.0 * SparseThresholdDpHistogram.MAX_PUBLIC_KEYS
                * Math.scalb(1.0, -63)).isLessThan(1.13e-13);
        assertThat(Math.log(2.0)).isLessThan(1.0);

        // For shared keys and an add/remove neighbor, log2 likelihood ratio
        // equals |y-(h+1)| - |y-h| and has absolute value at most one.
        for (int h = 0; h < 10; h++) {
            for (int y = -30; y <= 40; y++) {
                int difference = Math.abs(y - (h + 1)) - Math.abs(y - h);
                assertThat(Math.abs(difference)).isLessThanOrEqualTo(1);
            }
        }
    }

    @Test void invalidUniverseOrUnknownPersonContributionFails() {
        assertThatThrownBy(() -> new SparseThresholdDpHistogram(List.of("a", "a")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SparseThresholdDpHistogram(List.of("a"))
                .release(Map.of(UUID.randomUUID(), "b")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SparseThresholdDpHistogram(List.of("a"))
                .release(Map.of(new UUID(0, 0), "a")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static final class ScriptedRandom extends SecureRandom {
        private final ArrayDeque<Long> values = new ArrayDeque<>();
        ScriptedRandom(long... draws) {
            for (long value : draws) values.addLast(value);
        }
        @Override public long nextLong() {
            if (values.isEmpty()) throw new AssertionError("Unexpected random draw");
            return values.removeFirst();
        }
        int remaining() { return values.size(); }
    }
}
