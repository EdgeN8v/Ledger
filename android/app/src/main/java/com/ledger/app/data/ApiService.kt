package com.ledger.app.data

import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

interface ApiService {

    @POST("auth/register")
    suspend fun register(@Body body: RegisterRequest): TokenResponse

    @POST("auth/join")
    suspend fun join(@Body body: JoinRequest): TokenResponse

    @FormUrlEncoded
    @POST("auth/login")
    suspend fun login(
        @Field("username") username: String,
        @Field("password") password: String
    ): TokenResponse

    @POST("ingest")
    suspend fun ingest(@Body body: IngestRequest): IngestResult

    @GET("accounts")
    suspend fun listAccounts(): AccountsOverviewDto

    @POST("accounts")
    suspend fun createAccount(@Body body: AccountCreateRequest): AccountsOverviewDto

    @PUT("accounts/{id}")
    suspend fun updateAccount(@Path("id") id: Int, @Body body: Map<String, @JvmSuppressWildcards Any?>): AccountsOverviewDto

    @GET("reports/latest")
    suspend fun latestReport(): ReportDto

    @GET("trips")
    suspend fun listTrips(): List<TripDto>

    @GET("trips/suggestions")
    suspend fun tripSuggestions(): List<TripSuggestionDto>

    @POST("trips/suggestions/dismiss")
    suspend fun dismissTripSuggestion(@Body body: TripRefRequest)

    @POST("trips")
    suspend fun createTrip(@Body body: TripInRequest): TripDto

    @PUT("trips/{id}")
    suspend fun updateTrip(@Path("id") id: Int, @Body body: TripInRequest): TripDto

    @GET("trips/receivable")
    suspend fun receivable(): List<ReceivableTripDto>

    @POST("trips/{id}/add/{tx}")
    suspend fun addToTrip(@Path("id") id: Int, @Path("tx") tx: Int): TripDto

    @POST("trips/{id}/remove/{tx}")
    suspend fun removeFromTrip(@Path("id") id: Int, @Path("tx") tx: Int)

    @POST("trips/{id}/settle")
    suspend fun settleTrip(@Path("id") id: Int, @Body body: SettleRequest): TripDto

    @POST("trips/{id}/unsettle")
    suspend fun unsettleTrip(@Path("id") id: Int): TripDto

    @DELETE("trips/{id}")
    suspend fun deleteTrip(@Path("id") id: Int)

    @GET("plan")
    suspend fun getPlan(): PlanDto

    @PUT("plan")
    suspend fun setPlan(@Body body: PlanInRequest): PlanDto

    @POST("plan/fixed-mark")
    suspend fun markFixed(@Body body: FixedMarkRequest): PlanDto

    @GET("plan/reminders")
    suspend fun billReminders(): List<BillReminderDto>

    @GET("plan/status")
    suspend fun planStatus(): PlanStatusDto

    @GET("analysis/cycle")
    suspend fun analysisCycle(@Query("year") year: Int?, @Query("month") month: Int?, @Query("include_business") includeBusiness: Boolean = false): CycleAnalysisDto

    @GET("analysis/year")
    suspend fun analysisYear(@Query("year") year: Int, @Query("include_business") includeBusiness: Boolean = false): YearAnalysisDto

    @GET("auth/me")
    suspend fun me(): UserDto

    @GET("categories")
    suspend fun listCategories(): List<CategoryDto>

    @POST("transactions/parse")
    suspend fun parseTransaction(@Body body: ParseRequestBody): ParseResultDto

    @POST("transactions")
    suspend fun createTransaction(@Body body: TransactionCreateRequest): TransactionDto

    @GET("transactions")
    suspend fun listTransactions(
        @Query("date_from") dateFrom: String? = null,
        @Query("date_to") dateTo: String? = null,
        @Query("category_id") categoryId: Int? = null,
        @Query("tx_type") txType: String? = null,
        @Query("kind") kind: String? = null,
        @Query("q") q: String? = null,
        @Query("needs_review") needsReview: Boolean? = null,
        @Query("trip_id") tripId: Int? = null,
        @Query("exclude_business") excludeBusiness: Boolean = false,
        @Query("business_only") businessOnly: Boolean = false,
        @Query("sort") sort: String? = null,
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0
    ): List<TransactionDto>

    @PUT("transactions/{id}")
    suspend fun updateTransaction(@Path("id") id: Int, @Body body: Map<String, @JvmSuppressWildcards Any?>): TransactionDto

    @DELETE("transactions/{id}")
    suspend fun deleteTransaction(@Path("id") id: Int)

    @GET("transactions/summary/monthly")
    suspend fun monthlySummary(@Query("year") year: Int, @Query("month") month: Int, @Query("include_business") includeBusiness: Boolean = false): MonthSummaryDto
}
