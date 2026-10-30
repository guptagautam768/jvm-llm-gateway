package dev.gautamgupta.gateway.persistence.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class PartitionPlanTest {

    @Test
    void namesRoundTripAndRetentionDropsOnlyOlderDays() {
        LocalDate today = LocalDate.of(2026, 10, 30);
        assertThat(PartitionPlan.name(LocalDate.of(2020, 1, 1))).isEqualTo("usage_ledger_p20200101");
        assertThat(PartitionPlan.dayOf("usage_ledger_p20200101")).contains(LocalDate.of(2020, 1, 1));
        assertThat(PartitionPlan.dayOf("usage_ledger")).isEmpty();
        assertThat(PartitionPlan.dayOf("usage_ledger_pnotaday")).isEmpty();

        assertThat(PartitionPlan.shouldDrop(today.minusDays(31), today, 30)).isTrue();
        assertThat(PartitionPlan.shouldDrop(today.minusDays(30), today, 30)).isFalse();
        assertThat(PartitionPlan.shouldDrop(today, today, 30)).isFalse();

        assertThat(PartitionPlan.ensureDays(today, 3))
                .containsExactly(today.minusDays(1), today, today.plusDays(1), today.plusDays(2), today.plusDays(3));
    }
}
