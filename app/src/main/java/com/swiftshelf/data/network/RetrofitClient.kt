package com.swiftshelf.data.network

import com.google.gson.GsonBuilder
import com.swiftshelf.BuildConfig
import com.swiftshelf.data.model.RefreshResponse
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object RetrofitClient {

    private var retrofit: Retrofit? = null
    @Volatile private var apiToken: String? = null
    @Volatile private var baseUrl: String? = null
    @Volatile private var canAttemptRefresh: Boolean = false

    // Gson instance with HTML escaping disabled so passwords with <, >, &, etc. are
    // serialized as literal characters rather than \u003c Unicode escapes.
    private val gson = GsonBuilder().disableHtmlEscaping().create()

    // Lock to prevent concurrent token refresh races
    private val refreshLock = Any()

    // Shared cookie jar for all clients so refresh token cookies are preserved.
    // cookieStore is at singleton scope so clearCookies() can reach it.
    private val cookieStore = ConcurrentHashMap<String, List<Cookie>>()
    private val cookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookieStore[url.host] = cookies
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            return cookieStore[url.host] ?: emptyList()
        }
    }

    /**
     * Clears all stored cookies (call before a fresh login and on logout).
     * Prevents stale session cookies from being sent with new requests.
     */
    fun clearCookies() {
        cookieStore.clear()
    }

    fun initialize(baseUrl: String, token: String, canAttemptRefresh: Boolean = false,
                   onTokenRefreshed: ((accessToken: String) -> Unit)? = null) {
        this.baseUrl = baseUrl
        apiToken = token
        this.canAttemptRefresh = canAttemptRefresh

        val authInterceptor = Interceptor { chain ->
            val original = chain.request()
            val currentToken = apiToken
            val requestBuilder = original.newBuilder()
                .apply { if (currentToken != null) header("Authorization", "Bearer $currentToken") }
                .method(original.method, original.body)
            chain.proceed(requestBuilder.build())
        }

        val tokenRefreshInterceptor = Interceptor { chain ->
            val request = chain.request()
            val response = chain.proceed(request)
            // Only attempt refresh for JWT sessions (cookie-based).
            // API key sessions have no refresh cookie so 401s propagate as-is.
            if (response.code == 401 && canAttemptRefresh && !request.url.encodedPath.endsWith("auth/refresh")) {
                val newToken = synchronized(refreshLock) {
                    // If another thread already refreshed, reuse the updated token
                    val requestToken = request.header("Authorization")?.removePrefix("Bearer ")
                    if (requestToken == apiToken) {
                        val refreshResult = attemptTokenRefresh()
                        if (refreshResult != null) {
                            apiToken = refreshResult.accessToken
                            // Notify caller so the new access token can be persisted
                            onTokenRefreshed?.invoke(refreshResult.accessToken)
                        }
                        refreshResult?.accessToken
                    } else {
                        apiToken
                    }
                }
                if (newToken != null) {
                    // Only close the 401 response once we have a replacement token
                    response.close()
                    val newRequest = request.newBuilder()
                        .header("Authorization", "Bearer $newToken")
                        .build()
                    return@Interceptor chain.proceed(newRequest)
                }
            }
            response
        }

        val loggingLevel = if (BuildConfig.DEBUG) {
            HttpLoggingInterceptor.Level.BODY
        } else {
            HttpLoggingInterceptor.Level.NONE
        }
        val loggingInterceptor = HttpLoggingInterceptor { message ->
            android.util.Log.d("SwiftShelf/HTTP", message)
        }.apply {
            level = loggingLevel
            redactHeader("Authorization")
            android.util.Log.d("RetrofitClient", "Initializing: url=$baseUrl tokenLen=${token.length}")
        }

        val client = OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .addInterceptor(authInterceptor)
            .addInterceptor(tokenRefreshInterceptor)
            .addInterceptor(loggingInterceptor)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
    }

    private fun attemptTokenRefresh(): RefreshResponse? {
        val url = baseUrl ?: return null
        return try {
            val client = OkHttpClient.Builder()
                .cookieJar(cookieJar)
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
            val refreshUrl = "${url.trimEnd('/')}/auth/refresh"
            // The refresh token cookie is sent automatically by the shared cookie jar.
            val request = Request.Builder()
                .url(refreshUrl)
                .post("{}".toRequestBody("application/json".toMediaType()))
                .build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string()
                gson.fromJson(body, RefreshResponse::class.java)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Create an unauthenticated API client for login requests.
     * Uses the shared cookie jar so that refresh token cookies from login are retained.
     */
    fun createUnauthenticatedApi(baseUrl: String): AudiobookshelfApi {
        val loggingLevel = if (BuildConfig.DEBUG) {
            HttpLoggingInterceptor.Level.BODY
        } else {
            HttpLoggingInterceptor.Level.NONE
        }
        val loggingInterceptor = HttpLoggingInterceptor { message ->
            // Redact password values in logged JSON bodies.
            // Uses a non-greedy match to avoid issues with adjacent JSON fields.
            // Note: does not handle passwords containing literal backslash sequences;
            // those cases are uncommon and do not affect server-side authentication.
            val sanitized = message.replace(
                Regex(""""password"\s*:\s*".*?""""),
                """"password":"[REDACTED]""""
            )
            android.util.Log.d("SwiftShelf/HTTP", sanitized)
        }.apply {
            level = loggingLevel
            // Redact refresh token cookies from logs
            redactHeader("Cookie")
            redactHeader("Set-Cookie")
        }

        val client = OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .addInterceptor(loggingInterceptor)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(AudiobookshelfApi::class.java)
    }

    fun getApi(): AudiobookshelfApi {
        return retrofit?.create(AudiobookshelfApi::class.java)
            ?: throw IllegalStateException("RetrofitClient not initialized. Call initialize() first.")
    }

    fun isInitialized(): Boolean = retrofit != null
}
