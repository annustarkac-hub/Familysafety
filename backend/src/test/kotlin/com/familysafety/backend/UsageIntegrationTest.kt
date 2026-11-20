package com.familysafety.backend

import com.familysafety.backend.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.YearMonth

class UsageIntegrationTest : IntegrationTestBase() {
    private val today: LocalDate = LocalDate.now(ZoneOffset.UTC)

    private fun day(date: LocalDate, vararg apps: Triple<String, Long, Int>) = mapOf(
        "date" to date.toString(),
        "apps" to apps.map { (pkg, ms, launches) -> mapOf("packageName" to pkg, "appLabel" to pkg.substringAfterLast('.'),
            "foregroundMs" to ms, "launchCount" to launches, "lastUsedAt" to date.atTime(12, 0).toInstant(ZoneOffset.UTC).toString()) },
    )

    @Test
    fun `child syncs usage and parent reads daily, weekly, monthly and ranking`() {
        val (parent, child) = pairedFamily()
        val sync = post("/api/v1/usage/sync", mapOf("timezone" to "UTC", "days" to listOf(
            day(today, Triple("com.example.video", 3_600_000, 4), Triple("com.example.chat", 1_200_000, 10)),
            day(today.minusDays(1), Triple("com.example.video", 600_000, 1), Triple("com.example.game", 2_400_000, 2)),
        )), child.accessToken)
        assertThat(sync.status).isEqualTo(200)
        assertThat(sync.body!!.at("/acceptedRecords").asInt()).isEqualTo(4)

        val daily = get("/api/v1/usage/daily?deviceId=${child.deviceId}&date=$today", parent.accessToken)
        assertThat(daily.status).isEqualTo(200)
        assertThat(daily.body!!.at("/totalForegroundMs").asLong()).isEqualTo(4_800_000)
        assertThat(daily.body!!.at("/apps/0/packageName").asText()).isEqualTo("com.example.video")

        val weekly = get("/api/v1/usage/weekly?deviceId=${child.deviceId}&endDate=$today", parent.accessToken)
        assertThat(weekly.body!!.at("/days").size()).isEqualTo(7)
        assertThat(weekly.body!!.at("/totalForegroundMs").asLong()).isEqualTo(7_800_000)
        assertThat(weekly.body!!.at("/averageDailyMs").asLong()).isEqualTo(3_900_000)
        assertThat(weekly.body!!.at("/topApps/0/packageName").asText()).isEqualTo("com.example.video")
        assertThat(weekly.body!!.at("/topApps/0/totalForegroundMs").asLong()).isEqualTo(4_200_000)

        val monthly = get("/api/v1/usage/monthly?deviceId=${child.deviceId}&month=${YearMonth.from(today)}", parent.accessToken)
        assertThat(monthly.status).isEqualTo(200)
        assertThat(monthly.body!!.at("/days").size()).isEqualTo(YearMonth.from(today).lengthOfMonth())

        val ranking = get("/api/v1/usage/applications?deviceId=${child.deviceId}&from=${today.minusDays(6)}&to=$today", parent.accessToken)
        val packages = ranking.body!!.at("/applications").map { it.at("/packageName").asText() }
        assertThat(packages).containsExactly("com.example.video", "com.example.game", "com.example.chat")
        assertThat(ranking.body!!.at("/applications/0/rank").asInt()).isEqualTo(1)
        assertThat(ranking.body!!.at("/applications/0/daysUsed").asInt()).isEqualTo(2)

        // the child may read their own data (transparency)
        assertThat(get("/api/v1/usage/daily?deviceId=${child.deviceId}&date=$today", child.accessToken).status).isEqualTo(200)
    }

    @Test
    fun `sync is idempotent snapshot replacement`() {
        val (parent, child) = pairedFamily()
        val payload = mapOf("days" to listOf(day(today, Triple("com.example.video", 1_000_000, 1))))
        repeat(3) { assertThat(post("/api/v1/usage/sync", payload, child.accessToken).status).isEqualTo(200) }
        assertThat(get("/api/v1/usage/daily?deviceId=${child.deviceId}&date=$today", parent.accessToken).body!!.at("/totalForegroundMs").asLong())
            .isEqualTo(1_000_000)
        post("/api/v1/usage/sync", mapOf("days" to listOf(day(today, Triple("com.example.video", 1_500_000, 2)))), child.accessToken)
        assertThat(get("/api/v1/usage/daily?deviceId=${child.deviceId}&date=$today", parent.accessToken).body!!.at("/totalForegroundMs").asLong())
            .isEqualTo(1_500_000)
    }

    @Test
    fun `sync validation rejects bad data`() {
        val (_, child) = pairedFamily()
        val future = post("/api/v1/usage/sync", mapOf("days" to listOf(day(today.plusDays(5), Triple("com.a", 1, 1)))), child.accessToken)
        assertThat(future.status).isEqualTo(400)
        assertThat(future.text("/code")).isEqualTo("INVALID_USAGE_DATE")
        val tooMuch = post("/api/v1/usage/sync", mapOf("days" to listOf(day(today, Triple("com.a", 50_000_000, 1), Triple("com.b", 50_000_000, 1)))), child.accessToken)
        assertThat(tooMuch.status).isEqualTo(400)
        assertThat(tooMuch.text("/code")).isEqualTo("INVALID_USAGE_TOTAL")
        val badPkg = post("/api/v1/usage/sync", mapOf("days" to listOf(day(today, Triple("not a package!", 1, 1)))), child.accessToken)
        assertThat(badPkg.status).isEqualTo(400)
        val empty = post("/api/v1/usage/sync", mapOf("days" to emptyList<Any>()), child.accessToken)
        assertThat(empty.status).isEqualTo(400)
    }

    @Test
    fun `unpaired devices and parents cannot sync`() {
        val loneChild = register("CHILD")
        val r = post("/api/v1/usage/sync", mapOf("days" to listOf(day(today, Triple("com.a", 1, 1)))), loneChild.accessToken)
        assertThat(r.status).isEqualTo(409)
        assertThat(r.text("/code")).isEqualTo("DEVICE_NOT_PAIRED")
        val (parent, _) = pairedFamily()
        assertThat(post("/api/v1/usage/sync", mapOf("days" to listOf(day(today, Triple("com.a", 1, 1)))), parent.accessToken).status).isEqualTo(403)
    }

    @Test
    fun `exceeding the daily limit raises a single deduplicated alert`() {
        val (parent, child) = pairedFamily()
        val settings = call("PUT", "/api/v1/safety/settings", mapOf("deviceId" to child.deviceId, "dailyLimitMinutes" to 30,
            "limitAlertsEnabled" to true, "screenSharingEnabled" to true, "usageRetentionDays" to 60), parent.accessToken)
        assertThat(settings.status).isEqualTo(200)
        repeat(2) {
            post("/api/v1/usage/sync", mapOf("timezone" to "UTC", "days" to listOf(day(today, Triple("com.example.game", 3_600_000, 3)))), child.accessToken)
        }
        val alerts = get("/api/v1/safety/alerts", parent.accessToken).body!!.at("/alerts")
        assertThat(alerts.count { it.at("/type").asText() == "SCREEN_TIME_LIMIT_EXCEEDED" }).isEqualTo(1)
        // child sees the alert about their own device too
        val childAlerts = get("/api/v1/safety/alerts", child.accessToken).body!!.at("/alerts")
        assertThat(childAlerts.map { it.at("/type").asText() }).contains("SCREEN_TIME_LIMIT_EXCEEDED")
    }
}
