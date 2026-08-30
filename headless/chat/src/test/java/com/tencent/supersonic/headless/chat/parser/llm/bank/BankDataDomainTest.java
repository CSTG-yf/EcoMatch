package com.tencent.supersonic.headless.chat.parser.llm.bank;

import com.tencent.supersonic.headless.api.pojo.bank.BankDataDomain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Thread-safe lazy-cache semantics of the bank data-date domain singleton (synthetic dates). */
class BankDataDomainTest {

    @BeforeEach
    @AfterEach
    void resetDataDomain() {
        BankDataDomain.reset();
    }

    @Test
    void startsUninitializedSoGuardsFallOpen() {
        assertNull(BankDataDomain.current());
    }

    @Test
    void firstValidObservationWinsAndLaterOnesAreIgnored() {
        assertTrue(BankDataDomain.tryInitialize(LocalDate.of(2024, 1, 1),
                LocalDate.of(2025, 12, 31)));

        assertFalse(BankDataDomain.tryInitialize(LocalDate.of(2025, 1, 1),
                LocalDate.of(2025, 6, 30)));

        BankDataDomain domain = BankDataDomain.current();
        assertTrue(domain != null
                && LocalDate.of(2024, 1, 1).equals(domain.minDataDate())
                && LocalDate.of(2025, 12, 31).equals(domain.maxDataDate()));
    }

    @Test
    void rejectsNullAndInvertedObservationsWithoutInitializing() {
        assertFalse(BankDataDomain.tryInitialize(null, LocalDate.of(2025, 12, 31)));
        assertFalse(BankDataDomain.tryInitialize(LocalDate.of(2024, 1, 1), null));
        assertFalse(BankDataDomain.tryInitialize(LocalDate.of(2025, 12, 31),
                LocalDate.of(2024, 1, 1)));
        assertNull(BankDataDomain.current());
    }

    @Test
    void containsCoversTheClosedRangeIncludingBothBoundaries() {
        BankDataDomain.tryInitialize(LocalDate.of(2024, 1, 1), LocalDate.of(2025, 12, 31));
        BankDataDomain domain = BankDataDomain.current();

        assertTrue(domain.contains(LocalDate.of(2024, 1, 1)));
        assertTrue(domain.contains(LocalDate.of(2025, 12, 31)));
        assertTrue(domain.contains(LocalDate.of(2025, 6, 30)));
        assertFalse(domain.contains(LocalDate.of(2023, 12, 31)));
        assertFalse(domain.contains(LocalDate.of(2026, 1, 1)));
        assertFalse(domain.contains(null));
    }

    @Test
    void resetDropsTheCacheSoGuardsFallOpenAgain() {
        BankDataDomain.tryInitialize(LocalDate.of(2024, 1, 1), LocalDate.of(2025, 12, 31));
        BankDataDomain.reset();
        assertNull(BankDataDomain.current());
    }
}
