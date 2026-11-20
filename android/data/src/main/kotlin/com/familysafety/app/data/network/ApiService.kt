package com.familysafety.app.data.network

import retrofit2.Call
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

interface ApiService {
    @POST("api/v1/auth/register") suspend fun register(@Body body: RegisterBody): TokenResponseDto
    @POST("api/v1/auth/login") suspend fun login(@Body body: LoginBody): TokenResponseDto
    @POST("api/v1/auth/logout") suspend fun logout(): Response<Unit>
    @GET("api/v1/auth/me") suspend fun me(): MeDto

    @POST("api/v1/pairing/create") suspend fun createPairing(@Body body: CreatePairingBody): CreatePairingDto
    @POST("api/v1/pairing/accept") suspend fun acceptPairing(@Body body: AcceptPairingBody): AcceptPairingDto

    @GET("api/v1/devices") suspend fun devices(): List<DeviceDto>
    @GET("api/v1/devices/{id}") suspend fun device(@Path("id") id: String): DeviceDto
    @HTTP(method = "DELETE", path = "api/v1/devices/{id}", hasBody = true)
    suspend fun revokeDevice(@Path("id") id: String, @Body body: RevokeBody): Response<Unit>
    @POST("api/v1/devices/{id}/disconnect-request") suspend fun disconnectRequest(@Path("id") id: String): Response<Unit>

    @POST("api/v1/usage/sync") suspend fun syncUsage(@Body body: UsageSyncBody): UsageSyncDto
    @GET("api/v1/usage/daily") suspend fun dailyUsage(@Query("deviceId") deviceId: String, @Query("date") date: String): DailyUsageDto
    @GET("api/v1/usage/weekly") suspend fun weeklyUsage(@Query("deviceId") deviceId: String, @Query("endDate") endDate: String): PeriodUsageDto
    @GET("api/v1/usage/monthly") suspend fun monthlyUsage(@Query("deviceId") deviceId: String, @Query("month") month: String): PeriodUsageDto
    @GET("api/v1/usage/applications")
    suspend fun applications(
        @Query("deviceId") deviceId: String, @Query("from") from: String, @Query("to") to: String, @Query("limit") limit: Int,
    ): AppRankingResponseDto

    @POST("api/v1/screen-sharing/request") suspend fun requestScreenShare(@Body body: ScreenShareRequestBody): ScreenShareSessionDto
    @POST("api/v1/screen-sharing/approve") suspend fun approveScreenShare(@Body body: SessionRefBody): ScreenShareSessionDto
    @POST("api/v1/screen-sharing/reject") suspend fun rejectScreenShare(@Body body: SessionRefBody): ScreenShareSessionDto
    @POST("api/v1/screen-sharing/stop") suspend fun stopScreenShare(@Body body: StopBody): ScreenShareSessionDto
    @GET("api/v1/screen-sharing/status")
    suspend fun screenShareStatus(@Query("sessionId") sessionId: String? = null, @Query("deviceId") deviceId: String? = null): ScreenShareStatusDto

    @GET("api/v1/safety/alerts")
    suspend fun alerts(@Query("deviceId") deviceId: String?, @Query("unacknowledgedOnly") unacknowledgedOnly: Boolean, @Query("limit") limit: Int = 50): SafetyAlertListDto
    @POST("api/v1/safety/alerts") suspend fun createAlert(@Body body: CreateAlertBody): SafetyAlertDto
    @POST("api/v1/safety/alerts/{id}/acknowledge") suspend fun acknowledgeAlert(@Path("id") id: String): SafetyAlertDto
    @GET("api/v1/safety/settings") suspend fun safetySettings(@Query("deviceId") deviceId: String): SafetySettingsDto
    @PUT("api/v1/safety/settings") suspend fun updateSafetySettings(@Body body: SafetySettingsDto): SafetySettingsDto
}

/** Unauthenticated, synchronous API used by the OkHttp Authenticator for token refresh. */
interface RefreshApi {
    @POST("api/v1/auth/refresh") fun refresh(@Body body: RefreshBody): Call<TokenResponseDto>
}
