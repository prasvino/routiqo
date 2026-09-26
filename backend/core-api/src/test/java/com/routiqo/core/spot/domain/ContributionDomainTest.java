package com.routiqo.core.spot.domain;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Random;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContributionDomainTest {
    private static final Instant NOW = Instant.parse("2026-11-05T06:30:00Z");

    @Test void lifetimesFollowTheSpecTable() {
        assertThat(ContributionLife.forSignal(SpotCategory.TRAFFIC).base()).isEqualTo(Duration.ofMinutes(60));
        assertThat(ContributionLife.forSignal(SpotCategory.QUEUE).maximum()).isEqualTo(Duration.ofHours(2));
        assertThat(ContributionLife.forSignal(SpotCategory.FOOD).base()).isEqualTo(Duration.ofHours(24));
        assertThat(ContributionLife.forSignal(SpotCategory.RESTROOM).maximum()).isEqualTo(Duration.ofHours(36));
        assertThat(ContributionLife.forPost(PostType.TRAFFIC)).isEqualTo(
                new ContributionLife(Duration.ofMinutes(90), Duration.ofHours(3)));
        assertThat(ContributionLife.forPost(PostType.PLACE)).isEqualTo(
                new ContributionLife(Duration.ofHours(24), Duration.ofHours(36)));
    }

    @Test void captureTimeCanOnlyShortenLifeAndTooOldOrFutureIsRefused() {
        var life = ContributionLife.SHORT_SIGNAL;
        var live = life.timing(NOW.minusSeconds(600), NOW);
        assertThat(live.effectiveCreated()).isEqualTo(NOW.minusSeconds(600));
        assertThat(live.expiresAt()).isEqualTo(NOW.plusSeconds(3000));
        assertThat(live.maxExpiresAt()).isEqualTo(NOW.minusSeconds(600).plus(Duration.ofHours(2)));
        // A capture slightly after arrival (device clock ahead) uses the arrival time.
        assertThat(life.timing(NOW.plusSeconds(119), NOW).effectiveCreated()).isEqualTo(NOW);
        assertThatThrownBy(() -> life.timing(NOW.plusSeconds(121), NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> life.timing(NOW.minus(Duration.ofMinutes(60)), NOW))
                .isInstanceOf(ContributionLife.ContributionTooOld.class);
        assertThat(life.timing(NOW.minus(Duration.ofMinutes(59)), NOW).expiresAt()).isAfter(NOW);
    }

    @Test void stillTrueExtendsToHalfBaseLifeCappedAtMaximumAndNeverShortens() {
        var life = ContributionLife.SHORT_SIGNAL; // 60 min base, 2 h max
        Instant created = NOW.minus(Duration.ofMinutes(50));
        Instant expiry = created.plus(Duration.ofMinutes(60));
        assertThat(life.stillTrue(expiry, NOW, created)).isEqualTo(NOW.plus(Duration.ofMinutes(30)));
        Instant late = created.plus(Duration.ofMinutes(110));
        assertThat(life.stillTrue(late, late, created)).isEqualTo(created.plus(Duration.ofHours(2)));
        Instant far = NOW.plus(Duration.ofMinutes(50));
        assertThat(life.stillTrue(far, NOW, created)).isEqualTo(far);
    }

    @Test void signalValuesAreScopedToTheirCategory() {
        assertThat(SignalChoice.of(SpotCategory.QUEUE, "15_to_30")).isEqualTo(SignalChoice.QUEUE_15_TO_30);
        assertThat(SignalChoice.of(SpotCategory.RESTROOM, "avoid")).isEqualTo(SignalChoice.RESTROOM_AVOID);
        assertThatThrownBy(() -> SignalChoice.of(SpotCategory.FUEL, "slow"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(java.util.Arrays.stream(SignalChoice.values())
                .filter(choice -> choice.category() == SpotCategory.QUEUE)).hasSize(4);
    }

    @Test void postTextAcceptsTamilEnglishTanglishAndEmoji() {
        assertThat(PostText.normalize("  Toll queue moving fast  ")).isEqualTo("Toll queue moving fast");
        assertThat(PostText.normalize("சுங்கச்சாவடியில் நீண்ட வரிசை")).isEqualTo("சுங்கச்சாவடியில் நீண்ட வரிசை");
        assertThat(PostText.normalize("Semma rush da 😅 👨‍👩‍👧")).contains("👨‍👩‍👧");
        assertThat(PostText.normalize("ஸ்ரீ‍ராம் hotel good")).isNotEmpty();
        assertThat(PostText.normalize("NH 32 km 45, gate 2: 3 lanes open")).isNotEmpty();
        String max = "அ".repeat(200);
        assertThat(PostText.normalize(max)).isEqualTo(max);
    }

    @Test void postTextRejectsEmptyLongMultilineControlsAndContactDetails() {
        for (String bad : new String[] {"", "   ", "a".repeat(201), "line one\nline two", "tab\u0007bell",
                "\uD800 lone", "rtl‮override"})
            assertThatThrownBy(() -> PostText.normalize(bad)).isInstanceOf(PostText.InvalidPostText.class);
        for (String spam : new String[] {"Call 9876543210", "Call 98765 43210", "call 044-2345-678",
                "visit www.example.in", "see https://x.co", "best biryani at example.com",
                "mail me a@b.co", "Tamil digits ௯௮௭௬௫௪௩"})
            assertThatThrownBy(() -> PostText.normalize(spam)).isInstanceOf(PostText.ContactDetails.class);
    }

    @Test void theDraftWordListParsesAndIssuesOnlyItsOwnPairs() throws Exception {
        String text;
        try (InputStream input = getClass().getResourceAsStream("/spot/alias-words-v1.txt")) {
            text = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(text).contains("DRAFT: requires product owner approval and a Tamil-speaker review");
        var words = AliasWords.parse(text);
        assertThat(words.version()).isEqualTo("1-draft");
        assertThat(words.adjectives()).hasSizeGreaterThanOrEqualTo(100);
        assertThat(words.nouns()).hasSizeGreaterThanOrEqualTo(100);
        for (String excluded : new String[] {"Sun", "Sunny", "Leaf", "Lotus", "Hand", "Mango", "Kite",
                "Drum", "Torch", "Saffron", "Black", "Red", "Donkey", "Monkey", "Fair"})
            assertThat(words.adjectives()).doesNotContain(excluded);
        for (String excluded : new String[] {"Sun", "Leaf", "Lotus", "Hand", "Mango", "Kite", "Drum", "Torch",
                "Pot", "Lamp", "Star", "Cycle", "Elephant", "Broom", "Donkey", "Monkey"})
            assertThat(words.nouns()).doesNotContain(excluded);
        var random = new Random(7);
        var taken = new HashSet<String>();
        for (int index = 0; index < 500; index++) {
            String alias = words.pick(random::nextInt, taken::contains);
            assertThat(taken.add(alias)).isTrue();
            assertThat(words.issued(alias)).isTrue();
        }
        assertThat(words.issued("Blue Auto")).isFalse();
        assertThat(words.issued("Calm Auto 100")).isFalse();
    }

    @Test void aliasFallsBackToANumberWhenThePairIsTaken() {
        var words = AliasWords.parse("# version: t\n[adjectives]\n" + String.join("\n", names("Aa", 20))
                + "\n[nouns]\n" + String.join("\n", names("Bb", 20)));
        String alias = words.pick(bound -> 0, taken -> taken.equals("Aaa Bba"));
        assertThat(alias).isEqualTo("Aaa Bba 2");
        assertThatThrownBy(() -> words.pick(bound -> 0, taken -> true)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> AliasWords.parse("# version: t\n[adjectives]\nAaa\nAaa"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static String[] names(String prefix, int count) {
        String[] out = new String[count];
        for (int index = 0; index < count; index++) out[index] = prefix + (char) ('a' + index);
        return out;
    }
}
