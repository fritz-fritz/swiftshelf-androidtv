package com.swiftshelf.data.network

import com.google.gson.GsonBuilder
import com.swiftshelf.BuildConfig
import com.swiftshelf.data.model.RefreshResponse
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.Interceptor
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

    // Gson instance with HTML escaping disabled so passwords with <, >, &, etc. are
    // serialized as literal characters rather than \u003c Unicode escapes.
    private val gson = GsonBuilder().disableHtmlEscaping().create()

    // Lock to prevent concurrent token refresh races
    private val refreshLock = Any()

    // Shared cookie jar for all clients so refresh token cookies are preserved
    private val cookieJar = object : CookieJar {
        private val cookieStore = ConcurrentHashMap<String, List<Cookie>>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookieStore[url.host] = cookies
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            return cookieStore[url.host] ?: emptyList()
        }
    }

    fun initialize(baseUrl: String, token: String) {
        this.baseUrl = baseUrl
        apiToken = token

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
            // Don't attempt refresh on the refresh endpoint itself (avoid infinite loop)
            if (response.code == 401 && !request.url.encodedPath.endsWith("auth/refresh")) {
                val newToken = synchronized(refreshLock) {
                    // If another thread already refreshed, reuse the updated token
                    val requestToken = request.header("Authorization")?.removePrefix("Bearer ")
                    if (requestToken == apiToken) {
                        attemptTokenRefresh()?.also { apiToken = it }
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
            HttpLoggingInterceptor.Level.HEADERS
        } else {
            HttpLoggingInterceptor.Level.NONE
        }
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = loggingLevel
            android.util.Log.d("RetrofitClient", "Initializing with token: ${token.take(20)}...")
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

    private fun attemptTokenRefresh(): String? {
        val url = baseUrl ?: return null
        return try {
            val client = OkHttpClient.Builder()
                .cookieJar(cookieJar)
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
            val refreshUrl = "${url.trimEnd('/')}/auth/refresh"
            val request = Request.Builder()
                .url(refreshUrl)
                .post("".toRequestBody())
                .build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string()
                gson.fromJson(body, RefreshResponse::class.java)?.accessToken
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
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = loggingLevel
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
