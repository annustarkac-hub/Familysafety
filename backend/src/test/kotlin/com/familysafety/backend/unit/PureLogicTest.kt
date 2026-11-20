package com.familysafety.backend.unit

import com.familysafety.backend.model.AppUsageRecord
import com.familysafety.backend.service.PairingService
import com.familysafety.backend.service.UsageRanking
import com.familysafety.backend.service.inWindow
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

class PureLogicTest {
    @Test
    fun `bedtime window handles ranges across midnight`() {
        val start = LocalTime.of(21, 0)
        val end = LocalTime.of(7, 0)
        assertThat(inWindow(LocalTime.of(23, 30), start, end)).isTrue()
        assertThat(inWindow(LocalTime.of(3, 0), start, end)).isTrue()
        assertThat(inWindow(LocalTime.of(7, 0), start, end)).isFalse()
        assertThat(inWindow(LocalTime.of(12, 0), start, end)).isFalse()
        assertThat(inWindow(LocalTime.of(13, 0), LocalTime.of(12, 0), LocalTime.of(14, 0))).isTrue()
    }

    @Test
    fun `pairing code normalisation strips separators and upper-cases`() {
        assertThat(PairingService.normalize("ab2c-d3ef")).isEqualTo("AB2CD3EF")
        assertThat(PairingService.normalize(" ab2c d3ef ")).isEqualTo("AB2CD3EF")
        assertThat(PairingService.format("AB2CD3EF")).isEqualTo("AB2C-D3EF")
        assertThat(PairingService.ALPHABET).doesNotContain("0", "O", "1", "I", "L")
    }

    @Test
    fun `ranking aggregates across days and orders by time then package`() {
        val d = UUID.randomUUID()
        val day1 = LocalDate.of(2026, 1, 1)
        fun r(pkg: String, ms: Long, date: LocalDate, launches: Int = 1) =
            AppUsageRecord(deviceId = d, usageDate = date, packageName = pkg, appLabel = pkg.uppercase(), foregroundMs = ms,
                launchCount = launches, lastUsedAt = Instant.parse("${date}T10:00:00Z"))
        val ranked = UsageRanking.rank(listOf(r("b", 100, day1), r("a", 100, day1), r("c", 50, day1), r("c", 100, day1.plusDays(1), 3)), 10)
        assertThat(ranked.map { it.packageName }).containsExactly("c", "a", "b")
        assertThat(ranked[0].totalForegroundMs).isEqualTo(150)
        assertThat(ranked[0].launchCount).isEqualTo(4)
        assertThat(ranked[0].daysUsed).isEqualTo(2)
        assertThat(ranked.map { it.rank }).containsExactly(1, 2, 3)
        assertThat(UsageRanking.rank(listOf(r("a", 1, day1), r("b", 2, day1)), 1)).hasSize(1)
    }
}
