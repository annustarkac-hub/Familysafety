package com.familysafety.app.data

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.content.Context
import android.os.Process
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.familysafety.app.data.db.DeviceEntity
import com.familysafety.app.data.db.FamilySafetyDatabase
import com.familysafety.app.data.db.LocalAppUsageEntity
import com.familysafety.app.data.db.LocalDaySummaryEntity
import com.familysafety.app.data.network.toDomain
import com.familysafety.app.data.realtime.RealtimeClient
import com.familysafety.app.data.repository.DeviceRepositoryImpl
import com.familysafety.app.data.repository.SafetyRepositoryImpl
import com.familysafety.app.data.repository.UsageRepositoryImpl
import com.familysafety.app.data.usage.AndroidUsageCollector
import com.familysafety.app.domain.logic.UsageEvent
import com.familysafety.app.domain.model.AlertType
import com.familysafety.app.domain.model.AppError
import com.familysafety.app.domain.model.AppResult
import com.familysafety.app.domain.model.RealtimeEvent
import com.familysafety.app.domain.model.ScreenShareStatus
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class RoomDaoTest {
    private lateinit var db: FamilySafetyDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), FamilySafetyDatabase::class.java).allowMainThreadQueries().build()
    }
    @After fun tearDown() = db.close()

    @Test
    fun `replaceDay swaps a day's snapshot atomically`() = runBlocking {
        val dao = db.localUsageDao()
        dao.replaceDay(LocalDaySummaryEntity("2026-01-01", 100, null, null, 1, null), listOf(LocalAppUsageEntity("2026-01-01", "a", "A", 100, 1, null)))
        dao.replaceDay(LocalDaySummaryEntity("2026-01-01", 50, null, null, 2, null), listOf(LocalAppUsageEntity("2026-01-01", "b", "B", 50, 1, null)))
        assertEquals(listOf("b"), dao.appsFor("2026-01-01").map { it.packageName })
        assertEquals(50L, dao.observeSummary("2026-01-01").first()!!.totalForegroundMs)
        dao.markSynced(listOf("2026-01-01"), 99)
        assertEquals(99L, dao.summaries().single().syncedAtMs)
    }

    @Test
    fun `device cache is replaced and sorted`() = runBlocking {
        val dao = db.deviceDao()
        fun d(id: String, owner: String) = DeviceEntity(id, "Phone", null, null, null, null, "ACTIVE", null, "u", owner, null, null, null, null, null, null)
        dao.replaceAll(listOf(d("1", "zed"), d("2", "Amy")))
        assertEquals(listOf("Amy", "zed"), dao.observeAll().first().map { it.ownerDisplayName })
        dao.replaceAll(listOf(d("3", "Bo")))
        assertEquals(listOf("3"), dao.observeAll().first().map { it.id })
    }
}

@RunWith(RobolectricTestRunner::class)
class RepositoryParsingTest {
    private lateinit var env: TestEnv
    @Before fun setUp() { env = TestEnv(); runBlocking { env.store.save(session()) } }
    @After fun tearDown() = env.shutdown()

    @Test
    fun `devices are fetched and cached`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), FamilySafetyDatabase::class.java).allowMainThreadQueries().build()
        env.server.enqueue(MockResponse().setBody("""[{"id":"d9","deviceName":"Pixel","status":"ACTIVE","ownerUserId":"c1","ownerDisplayName":"Casey",
            "latestUsageDate":"2026-01-02","latestUsageForegroundMs":120000,"pairedAt":"2026-01-01T10:00:00Z"}]"""))
        val repo = DeviceRepositoryImpl(env.api, db.deviceDao())
        val r = repo.refresh()
        assertEquals("Casey", (r as AppResult.Success).value.single().ownerDisplayName)
        assertEquals(LocalDate.of(2026, 1, 2), repo.observeDevices().first().single().latestUsageDate)
        db.close()
    }

    @Test
    fun `daily usage and alerts are mapped to domain models`() = runBlocking {
        env.server.enqueue(MockResponse().setBody("""{"deviceId":"d1","date":"2026-01-02","totalForegroundMs":5000,"appCount":1,"dailyLimitMinutes":60,
            "apps":[{"packageName":"com.a","appLabel":"A","foregroundMs":5000,"launchCount":2}]}"""))
        val daily = UsageRepositoryImpl(env.api).daily("d1", LocalDate.of(2026, 1, 2)) as AppResult.Success
        assertEquals(60, daily.value.dailyLimitMinutes)
        assertEquals("/api/v1/usage/daily?deviceId=d1&date=2026-01-02", env.server.takeRequest().path)

        env.server.enqueue(MockResponse().setBody("""{"alerts":[{"id":"a1","familyId":"f","type":"HELP_REQUEST","severity":"CRITICAL","audience":"PARENTS",
            "title":"Help","createdAt":"2026-01-02T10:00:00Z"},{"id":"a2","familyId":"f","type":"SOMETHING_NEW","severity":"INFO","audience":"PARENTS","title":"x","createdAt":"2026-01-02T10:00:00Z"}]}"""))
        val alerts = SafetyRepositoryImpl(env.api).alerts() as AppResult.Success
        assertEquals(listOf(AlertType.HELP_REQUEST, AlertType.UNKNOWN), alerts.value.map { it.type })
    }

    @Test
    fun `unknown alert types are rejected locally`() = runBlocking {
        val r = SafetyRepositoryImpl(env.api).createAlert(AlertType.UNKNOWN, null)
        assertTrue((r as AppResult.Failure).error is AppError.Validation)
    }
}

@RunWith(RobolectricTestRunner::class)
class RealtimeParsingTest {
    private val client = RealtimeClient(OkHttpClient(), "https://api.example.test/", mockk(relaxed = true), mockk(relaxed = true), testJson,
        TestScope(), Dispatchers.Unconfined)

    @Test
    fun `websocket url is derived from the api base url`() {
        assertEquals("wss://api.example.test/ws", client.wsUrl)
    }

    @Test
    fun `parses server events`() {
        val req = client.parse("""{"type":"screen_share.requested","payload":{"id":"s1","deviceId":"d1","status":"REQUESTED","requestedByDisplayName":"Pat",
            "requestedAt":"2026-01-01T00:00:00Z","requestExpiresAt":"2026-01-01T00:01:30Z"}}""") as RealtimeEvent.ScreenShareRequested
        assertEquals(ScreenShareStatus.REQUESTED, req.session.status)
        val sig = client.parse("""{"type":"signal","payload":{"sessionId":"s1","payload":{"kind":"ice","candidate":"c","sdpMid":"0","sdpMLineIndex":0}}}""") as RealtimeEvent.Signal
        assertEquals("ice", sig.kind); assertEquals(0, sig.sdpMLineIndex)
        assertTrue(client.parse("""{"type":"device.revoked","payload":{"deviceId":"d1"}}""") is RealtimeEvent.DeviceRevoked)
        assertNull(client.parse("not json"))
        assertNull(client.parse("""{"type":"unknown.type"}"""))
    }
}

@RunWith(RobolectricTestRunner::class)
class UsagePermissionTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun collector() = AndroidUsageCollector(context, mockk(relaxed = true), mockk(relaxed = true), Dispatchers.Unconfined)

    @Test
    fun `usage access reflects the app-op mode`() {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        shadowOf(ops).setMode(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName, AppOpsManager.MODE_IGNORED)
        assertFalse(collector().hasUsageAccess())
        shadowOf(ops).setMode(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName, AppOpsManager.MODE_ALLOWED)
        assertTrue(collector().hasUsageAccess())
    }

    @Test
    fun `nothing is collected or uploaded without usage access`() = runBlocking {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        shadowOf(ops).setMode(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName, AppOpsManager.MODE_IGNORED)
        assertTrue(collector().collect(7).isEmpty())
        assertTrue((collector().syncNow() as AppResult.Failure).error is AppError.Validation)
    }

    @Test
    fun `event types map to the aggregation model`() {
        assertEquals(UsageEvent.Type.FOREGROUND, AndroidUsageCollector.mapType(UsageEvents.Event.ACTIVITY_RESUMED))
        assertEquals(UsageEvent.Type.BACKGROUND, AndroidUsageCollector.mapType(UsageEvents.Event.ACTIVITY_PAUSED))
        assertEquals(UsageEvent.Type.SCREEN_OFF, AndroidUsageCollector.mapType(UsageEvents.Event.SCREEN_NON_INTERACTIVE))
        assertNull(AndroidUsageCollector.mapType(UsageEvents.Event.CONFIGURATION_CHANGE))
    }
}
