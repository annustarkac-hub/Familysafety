package com.familysafety.app.data.di

import android.content.Context
import android.os.Build
import androidx.room.Room
import com.familysafety.app.core.security.KeystoreSecretCipher
import com.familysafety.app.core.security.SecretCipher
import com.familysafety.app.data.BuildConfig
import com.familysafety.app.data.db.DeviceDao
import com.familysafety.app.data.db.FamilySafetyDatabase
import com.familysafety.app.data.db.LocalUsageDao
import com.familysafety.app.data.db.NotifiedAlertDao
import com.familysafety.app.data.network.ApiService
import com.familysafety.app.data.network.AuthHeaderInterceptor
import com.familysafety.app.data.network.DeviceInfoBody
import com.familysafety.app.data.network.RefreshApi
import com.familysafety.app.data.network.TokenAuthenticator
import com.familysafety.app.data.notify.Notifier
import com.familysafety.app.data.realtime.RealtimeClient
import com.familysafety.app.data.realtime.RealtimeLifecycle
import com.familysafety.app.data.repository.AuthRepositoryImpl
import com.familysafety.app.data.repository.DeviceInfoProvider
import com.familysafety.app.data.repository.DeviceRepositoryImpl
import com.familysafety.app.data.repository.PairingRepositoryImpl
import com.familysafety.app.data.repository.SafetyRepositoryImpl
import com.familysafety.app.data.repository.ScreenShareRepositoryImpl
import com.familysafety.app.data.repository.SessionLifecycleListener
import com.familysafety.app.data.repository.UsageRepositoryImpl
import com.familysafety.app.data.session.SessionStore
import com.familysafety.app.data.session.TokenRefresher
import com.familysafety.app.data.usage.AndroidUsageCollector
import com.familysafety.app.data.webrtc.ScreenShareCoordinator
import com.familysafety.app.data.webrtc.WebRtcEngine
import com.familysafety.app.data.work.WorkScheduler
import com.familysafety.app.domain.repository.AuthRepository
import com.familysafety.app.domain.repository.DeviceRepository
import com.familysafety.app.domain.repository.PairingRepository
import com.familysafety.app.domain.repository.RealtimeRepository
import com.familysafety.app.domain.repository.SafetyRepository
import com.familysafety.app.domain.repository.ScreenShareRepository
import com.familysafety.app.domain.repository.UsageCollector
import com.familysafety.app.domain.repository.UsageRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier @Retention(AnnotationRetention.BINARY) annotation class ApplicationScope
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class IoDispatcher
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class BaseUrl
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class AppVersion

@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    @Provides @Singleton @ApplicationScope
    fun appScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides @IoDispatcher fun io(): CoroutineDispatcher = Dispatchers.IO

    @Provides @BaseUrl fun baseUrl(): String = BuildConfig.API_BASE_URL

    @Provides @Singleton
    fun json(): Json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

    @Provides @Singleton fun cipher(): SecretCipher = KeystoreSecretCipher()

    @Provides @Singleton
    fun sessionStore(@ApplicationContext ctx: Context, cipher: SecretCipher, json: Json) = SessionStore(ctx, cipher, json)

    /** Base client shared by REST and WebSocket. TLS is enforced by the network security config in release. */
    @Provides @Singleton
    fun baseClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .apply {
            if (BuildConfig.DEBUG) {
                addInterceptor(HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BASIC
                    redactHeader("Authorization")
                })
            }
        }
        .build()

    @Provides @Singleton
    fun refreshApi(base: OkHttpClient, json: Json, @BaseUrl baseUrl: String): RefreshApi = Retrofit.Builder()
        .baseUrl(baseUrl).client(base)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build().create(RefreshApi::class.java)

    @Provides @Singleton fun refresher(store: SessionStore, api: RefreshApi) = TokenRefresher(store, api)

    @Provides @Singleton
    fun api(base: OkHttpClient, store: SessionStore, refresher: TokenRefresher, json: Json, @BaseUrl baseUrl: String): ApiService {
        val client = base.newBuilder()
            .addInterceptor(AuthHeaderInterceptor(store))
            .authenticator(TokenAuthenticator(refresher))
            .build()
        return Retrofit.Builder().baseUrl(baseUrl).client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build().create(ApiService::class.java)
    }

    @Provides @Singleton
    fun database(@ApplicationContext ctx: Context): FamilySafetyDatabase =
        Room.databaseBuilder(ctx, FamilySafetyDatabase::class.java, "family_safety.db").fallbackToDestructiveMigration().build()

    @Provides fun deviceDao(db: FamilySafetyDatabase): DeviceDao = db.deviceDao()
    @Provides fun localUsageDao(db: FamilySafetyDatabase): LocalUsageDao = db.localUsageDao()
    @Provides fun notifiedDao(db: FamilySafetyDatabase): NotifiedAlertDao = db.notifiedAlertDao()

    @Provides @Singleton fun notifier(@ApplicationContext ctx: Context) = Notifier(ctx)
    @Provides @Singleton fun workScheduler(@ApplicationContext ctx: Context) = WorkScheduler(ctx)

    @Provides @Singleton
    fun realtimeClient(base: OkHttpClient, @BaseUrl baseUrl: String, store: SessionStore, refresher: TokenRefresher, json: Json,
                       @ApplicationScope scope: CoroutineScope, @IoDispatcher io: CoroutineDispatcher) =
        RealtimeClient(base, baseUrl, store, refresher, json, scope, io)

    @Provides @Singleton fun realtime(client: RealtimeClient): RealtimeRepository = client
    @Provides @Singleton fun realtimeLifecycle(realtime: RealtimeRepository, store: SessionStore) = RealtimeLifecycle(realtime, store)

    @Provides @IntoSet fun workListener(s: WorkScheduler): SessionLifecycleListener = s
    @Provides @IntoSet fun realtimeListener(l: RealtimeLifecycle): SessionLifecycleListener = l

    @Provides
    fun deviceInfo(store: SessionStore, @AppVersion version: String): DeviceInfoProvider = DeviceInfoProvider {
        DeviceInfoBody(
            installationId = store.installationId(),
            deviceName = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}".take(80),
            manufacturer = Build.MANUFACTURER.take(80), model = Build.MODEL.take(80),
            osVersion = "Android ${Build.VERSION.RELEASE}".take(32), appVersion = version.take(32),
        )
    }

    @Provides @Singleton
    fun authRepository(api: ApiService, store: SessionStore, info: DeviceInfoProvider, usage: LocalUsageDao, devices: DeviceDao,
                       notified: NotifiedAlertDao, listeners: Set<@JvmSuppressWildcards SessionLifecycleListener>,
                       @ApplicationScope scope: CoroutineScope): AuthRepository =
        AuthRepositoryImpl(api, store, info, usage, devices, notified, listeners, scope)

    @Provides @Singleton fun pairing(api: ApiService, store: SessionStore): PairingRepository = PairingRepositoryImpl(api, store)
    @Provides @Singleton fun devices(api: ApiService, dao: DeviceDao): DeviceRepository = DeviceRepositoryImpl(api, dao)
    @Provides @Singleton fun usage(api: ApiService): UsageRepository = UsageRepositoryImpl(api)
    @Provides @Singleton fun screenShare(api: ApiService): ScreenShareRepository = ScreenShareRepositoryImpl(api)
    @Provides @Singleton fun safety(api: ApiService): SafetyRepository = SafetyRepositoryImpl(api)

    @Provides @Singleton
    fun collector(@ApplicationContext ctx: Context, dao: LocalUsageDao, api: ApiService, @IoDispatcher io: CoroutineDispatcher): UsageCollector =
        AndroidUsageCollector(ctx, dao, api, io)

    @Provides @Singleton fun webRtc(@ApplicationContext ctx: Context) = WebRtcEngine(ctx)

    @Provides @Singleton
    fun coordinator(repo: ScreenShareRepository, realtime: RealtimeRepository, notifier: Notifier, @ApplicationScope scope: CoroutineScope) =
        ScreenShareCoordinator(repo, realtime, notifier, scope)
}
