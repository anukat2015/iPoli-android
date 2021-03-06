package io.ipoli.android.common.api

import io.ipoli.android.BuildConfig
import io.ipoli.android.Constants
import io.ipoli.android.common.datetime.startOfDayUTC
import kotlinx.coroutines.experimental.suspendCancellableCoroutine
import okhttp3.*
import org.json.JSONObject
import org.threeten.bp.LocalDate
import java.io.IOException
import java.lang.Exception
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Created by Polina Zhelyazkova <polina@mypoli.fun>
 * on 3/23/18.
 */

object Api {

    private val urlProvider = if (BuildConfig.DEBUG) DevUrlProvider() else ProdUrlProvider()
    private val httpClient = OkHttpClient().newBuilder()
        .readTimeout(Constants.API_READ_TIMEOUT_SECONDS.toLong(), TimeUnit.SECONDS)
        .retryOnConnectionFailure(false).build()

    /**
     * @throws MembershipStatusException
     */
    suspend fun getMembershipStatus(
        subscriptionId: String,
        token: String
    ): MembershipStatus {

        val params = mapOf(
            "subscription_id" to subscriptionId,
            "token" to token
        )

        val request = createPostRequest(params, urlProvider.getMembershipStatus)
        val response = callServer(request)
        if (!response.isSuccessful) {
            throw MembershipStatusException("Api membership status call failed with response ${response.message()}")
        }
        return toMembershipStatus(response)
    }

    private fun toMembershipStatus(response: Response): MembershipStatus {

        val jsonObject = JSONObject(response.body()!!.string())

        val startTimeMillis = jsonObject.getLong("start_time")
        val expiryTimeMillis = jsonObject.getLong("end_time")
        val autoRenewing = jsonObject.getBoolean("autorenew")

        return MembershipStatus(
            startDate = startTimeMillis.startOfDayUTC,
            expirationDate = expiryTimeMillis.startOfDayUTC,
            isAutoRenewing = autoRenewing
        )
    }

    private fun createPostRequest(params: Map<String, String>, url: URL) =
        Request.Builder()
            .url(url)
            .post(createPostRequestBody(params))
            .build()

    private fun createPostRequestBody(params: Map<String, String>): RequestBody {
        val jsonMediaType = MediaType.parse("application/json; charset=utf-8")
        val jsonContent = JSONObject(params)
        return RequestBody.create(jsonMediaType, jsonContent.toString())
    }

    private suspend fun callServer(request: Request) =
        try {
            httpClient.newCall(request).await()
        } catch (e: Exception) {
            throw ApiServerCallException("Api server call failed", e)
        }


    data class MembershipStatus(
        val startDate: LocalDate,
        val expirationDate: LocalDate,
        val isAutoRenewing: Boolean
    )

    class MembershipStatusException(message: String, cause: Throwable? = null) :
        Exception(message, cause)

    class PlayerMigrationException(message: String, cause: Throwable? = null) :
        Exception(message, cause)

    class ApiServerCallException(message: String, cause: Throwable? = null) :
        Exception(message, cause)
}

suspend fun Call.await(): Response {
    return suspendCancellableCoroutine { continuation ->
        enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response)
            }

            override fun onFailure(call: Call, e: IOException) {
                // Don't bother with resuming the continuation if it is already cancelled.
                if (continuation.isCancelled) return
                continuation.resumeWithException(e)
            }
        })

        continuation.invokeOnCancellation {
            try {
                cancel()
            } catch (ex: Throwable) {
                //Ignore cancel exception
            }
        }
    }
}