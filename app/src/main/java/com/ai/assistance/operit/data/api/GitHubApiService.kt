package com.ai.assistance.operit.data.api

import android.content.Context
import android.os.SystemClock
import com.ai.assistance.operit.data.preferences.GitHubAuthPreferences
import com.ai.assistance.operit.data.preferences.GitHubUser
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.*

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Serializable
data class GitHubRepository(
    val id: Long,
    val name: String,
    val full_name: String,
    val description: String?,
    val html_url: String,
    val clone_url: String,
    val stargazers_count: Int,
    val forks_count: Int,
    val language: String?,
    val topics: List<String> = emptyList(),
    val size: Int = 0,
    @SerialName("default_branch")
    val defaultBranch: String = "",
    val created_at: String,
    val updated_at: String,
    val owner: GitHubUser
)


@Serializable
data class CreateRepositoryRequest(
    val name: String,
    val description: String? = null,
    val homepage: String? = null,
    val `private`: Boolean = false,
    val has_issues: Boolean = true,
    val has_projects: Boolean = false,
    val has_wiki: Boolean = false,
    val auto_init: Boolean = false
)

@Serializable
data class CreateRepositoryContentRequest(
    val message: String,
    val content: String,
    val branch: String? = null,
    val sha: String? = null
)

@Serializable
data class GitHubRepositoryContentFile(
    val name: String,
    val path: String,
    val sha: String,
    val type: String
)

@Serializable
data class CreateReleaseRequest(
    val tag_name: String,
    val name: String? = null,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false
)

@Serializable
data class UpdateReleaseRequest(
    val tag_name: String? = null,
    val name: String? = null,
    val body: String? = null,
    val draft: Boolean? = null,
    val prerelease: Boolean? = null
)


@Serializable
data class GitHubRelease(
    val id: Long,
    val tag_name: String,
    val name: String?,
    val body: String?,
    val html_url: String,
    val upload_url: String? = null,
    val published_at: String? = null,
    val created_at: String,
    val prerelease: Boolean = false,
    val draft: Boolean = false,
    val assets: List<GitHubReleaseAsset> = emptyList()
)

@Serializable
data class GitHubReleaseAsset(
    val id: Long,
    val name: String,
    val browser_download_url: String,
    val size: Long,
    val download_count: Int,
    val content_type: String
)

/**
 * GitHub API服务类
 * 提供GitHub用户信息、仓库操作等功能
 */
class GitHubApiService(private val context: Context) {
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request()
            val builder = request.newBuilder()
                .addHeader("User-Agent", "Operit-MCP-Client")
            if (request.header("Accept") == null) {
                builder.addHeader("Accept", "application/vnd.github.v3+json")
            }
            val newRequest = builder.build()
            chain.proceed(newRequest)
        }
        .build()
    
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    
    private val authPreferences = GitHubAuthPreferences.getInstance(context)

    companion object {
        private const val TAG = "GitHubApiService"
        private const val GITHUB_API_BASE = "https://api.github.com"

        // 补丁检查与补丁安装之间共享的 release 列表缓存：检查阶段已经翻完全部分页，
        // 用户紧接着点击安装时不应再重复消耗一次全量 API 配额。缓存只保留很短时间，
        // 避免长期持有陈旧列表。
        private const val RELEASES_CACHE_TTL_MS = 60_000L
        private val releasesCache = ConcurrentHashMap<String, CachedReleases>()

        private data class CachedReleases(
            val releases: List<GitHubRelease>,
            val cachedAt: Long
        )
    }
    
    /**
     * 获取当前用户信息
     */
    suspend fun getCurrentUser(): Result<GitHubUser> = withContext(Dispatchers.IO) {
        val token = authPreferences.getCurrentAccessToken()
            ?: return@withContext Result.failure(Exception("No access token available"))
        getCurrentUserWithToken(token)
    }

    /** Validates a newly exchanged token before it is persisted as the active session. */
    suspend fun getCurrentUser(accessToken: String): Result<GitHubUser> = withContext(Dispatchers.IO) {
        getCurrentUserWithToken(accessToken)
    }

    private fun getCurrentUserWithToken(accessToken: String): Result<GitHubUser> {
        return try {
            val request = Request.Builder()
                .url("$GITHUB_API_BASE/user")
                .addHeader("Authorization", "Bearer $accessToken")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return Result.failure(Exception("HTTP ${response.code}: ${response.message}"))
                }
                val responseBody = response.body?.string()
                    ?: return Result.failure(Exception("Empty response body"))
                Result.success(json.decodeFromString<GitHubUser>(responseBody))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * 根据用户名获取GitHub用户信息
     */
    suspend fun getUser(username: String): Result<GitHubUser> = withContext(Dispatchers.IO) {
        try {
            val requestBuilder = Request.Builder()
                .url("$GITHUB_API_BASE/users/$username")
            
            // 如果用户已登录，添加认证头以提高API配额
            authPreferences.getAuthorizationHeader()?.let { authHeader ->
                requestBuilder.addHeader("Authorization", authHeader)
            }

            val response = client.newCall(requestBuilder.build()).execute()

            if (response.isSuccessful) {
                val responseBody = response.body?.string()
                if (responseBody != null) {
                    val user = json.decodeFromString<GitHubUser>(responseBody)
                    Result.success(user)
                } else {
                    Result.failure(Exception("Empty response body"))
                }
            } else {
                Result.failure(Exception("HTTP ${response.code}: ${response.message}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * 搜索仓库
     */
    suspend fun searchRepositories(
        query: String,
        sort: String = "stars",
        order: String = "desc",
        page: Int = 1,
        perPage: Int = 30
    ): Result<List<GitHubRepository>> = withContext(Dispatchers.IO) {
        try {
            val url = HttpUrl.Builder()
                .scheme("https")
                .host("api.github.com")
                .addPathSegment("search")
                .addPathSegment("repositories")
                .addQueryParameter("q", query)
                .addQueryParameter("sort", sort)
                .addQueryParameter("order", order)
                .addQueryParameter("page", page.toString())
                .addQueryParameter("per_page", perPage.toString())
                .build()
            
            val requestBuilder = Request.Builder()
                .url(url)
            
            // 如果用户已登录，添加认证头以提高API配额
            authPreferences.getAuthorizationHeader()?.let { authHeader ->
                requestBuilder.addHeader("Authorization", authHeader)
            }

            val response = client.newCall(requestBuilder.build()).execute()

            if (response.isSuccessful) {
                val responseBody = response.body?.string()
                if (responseBody != null) {
                    val searchResult = json.parseToJsonElement(responseBody).jsonObject
                    val itemsArray = searchResult["items"]?.jsonArray
                    val repositories = itemsArray?.map { item ->
                        json.decodeFromJsonElement(GitHubRepository.serializer(), item)
                    } ?: emptyList()
                    Result.success(repositories)
                } else {
                    Result.failure(Exception("Empty response body"))
                }
            } else {
                Result.failure(Exception("HTTP ${response.code}: ${response.message}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 获取用户仓库
     */
    suspend fun getUserRepositories(
        username: String? = null,
        type: String = "all",
        sort: String = "updated",
        page: Int = 1,
        perPage: Int = 30
    ): Result<List<GitHubRepository>> = withContext(Dispatchers.IO) {
        try {
            val url = if (username != null) {
                "$GITHUB_API_BASE/users/$username/repos"
            } else {
                "$GITHUB_API_BASE/user/repos"
            }
            
            val httpUrl = HttpUrl.Builder()
                .scheme("https")
                .host("api.github.com")
                .apply {
                    if (username != null) {
                        addPathSegment("users")
                        addPathSegment(username)
                        addPathSegment("repos")
                    } else {
                        addPathSegment("user")
                        addPathSegment("repos")
                    }
                }
                .addQueryParameter("type", type)
                .addQueryParameter("sort", sort)
                .addQueryParameter("page", page.toString())
                .addQueryParameter("per_page", perPage.toString())
                .build()
            
            val requestBuilder = Request.Builder().url(httpUrl)
            
            // 如果是获取当前用户的仓库，需要认证
            if (username == null) {
                val authHeader = authPreferences.getAuthorizationHeader()
                    ?: return@withContext Result.failure(Exception("No access token available"))
                requestBuilder.addHeader("Authorization", authHeader)
            }
            
            val response = client.newCall(requestBuilder.build()).execute()
            
            if (response.isSuccessful) {
                val responseBody = response.body?.string()
                if (responseBody != null) {
                    val repositories = json.decodeFromString<List<GitHubRepository>>(responseBody)
                    Result.success(repositories)
                } else {
                    Result.failure(Exception("Empty response body"))
                }
            } else {
                Result.failure(Exception("HTTP ${response.code}: ${response.message}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 获取仓库信息
     */
    suspend fun getRepository(
        owner: String,
        repo: String
    ): Result<GitHubRepository> = withContext(Dispatchers.IO) {
        try {
            val requestBuilder = Request.Builder()
                .url("$GITHUB_API_BASE/repos/$owner/$repo")
            
            // 如果用户已登录，添加认证头以提高API配额
            authPreferences.getAuthorizationHeader()?.let { authHeader ->
                requestBuilder.addHeader("Authorization", authHeader)
            }
            
            val response = client.newCall(requestBuilder.build()).execute()
            
            if (response.isSuccessful) {
                val responseBody = response.body?.string()
                if (responseBody != null) {
                    val repository = json.decodeFromString<GitHubRepository>(responseBody)
                    Result.success(repository)
                } else {
                    Result.failure(Exception("Empty response body"))
                }
            } else {
                Result.failure(Exception("HTTP ${response.code}: ${response.message}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * 获取仓库的Releases
     */
    suspend fun getRepositoryReleases(
        owner: String,
        repo: String,
        page: Int = 1,
        perPage: Int = 30
    ): Result<List<GitHubRelease>> = withContext(Dispatchers.IO) {
        try {
            val url = HttpUrl.Builder()
                .scheme("https")
                .host("api.github.com")
                .addPathSegment("repos")
                .addPathSegment(owner)
                .addPathSegment(repo)
                .addPathSegment("releases")
                .addQueryParameter("page", page.toString())
                .addQueryParameter("per_page", perPage.toString())
                .build()
            
            val requestBuilder = Request.Builder()
                .url(url)
            
            // 如果用户已登录，添加认证头以提高API配额
            authPreferences.getAuthorizationHeader()?.let { authHeader ->
                requestBuilder.addHeader("Authorization", authHeader)
            }
            
            val response = client.newCall(requestBuilder.build()).execute()
            
            if (response.isSuccessful) {
                val responseBody = response.body?.string()
                if (responseBody != null) {
                    val releases = json.decodeFromString<List<GitHubRelease>>(responseBody)
                    Result.success(releases)
                } else {
                    Result.failure(Exception("Empty response body"))
                }
            } else {
                Result.failure(Exception("HTTP ${response.code}: ${response.message}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 获取仓库的全部 Releases（自动翻页直到取完）。
     *
     * 增量补丁链依赖“能看见完整 release 列表”：GitHub 的 /releases 是分页接口，
     * 只取第一页会把发布顺序靠后的历史 release（例如被 daily dev 版本挤到后面的
     * 正式版首跳补丁）漏掉，导致补丁链断链。这里循环翻页直到返回不足一页为止，
     * 并用最大页数保护异常仓库（例如长期存在大量 release 的仓库）。
     */
    suspend fun getAllRepositoryReleases(
        owner: String,
        repo: String,
        perPage: Int = 100
    ): Result<List<GitHubRelease>> = withContext(Dispatchers.IO) {
        try {
            val cacheKey = "$owner/$repo/$perPage"
            releasesCache[cacheKey]?.let { cached ->
                if (SystemClock.elapsedRealtime() - cached.cachedAt <= RELEASES_CACHE_TTL_MS) {
                    return@withContext Result.success(cached.releases)
                }
                // 条件删除：避免并发下把另一个协程刚写入的新值删掉。
                releasesCache.remove(cacheKey, cached)
            }

            val releases = mutableListOf<GitHubRelease>()
            val seenIds = mutableSetOf<Long>()
            var page = 1
            var finished = false
            // GitHub 通过 Link 头给出 rel="last" 总页数，优先用它精确翻到最后一页，
            // 避免“翻到空页才停”在最后一页恰好满 perPage 时多请求一次空页。
            // 解析失败时退回按返回不足一页判断，并保留最大页数保护异常仓库。
            val maxPages = 50
            var lastPage: Int? = null
            while (page <= maxPages) {
                val url = HttpUrl.Builder()
                    .scheme("https")
                    .host("api.github.com")
                    .addPathSegment("repos")
                    .addPathSegment(owner)
                    .addPathSegment(repo)
                    .addPathSegment("releases")
                    .addQueryParameter("page", page.toString())
                    .addQueryParameter("per_page", perPage.toString())
                    .build()

                val requestBuilder = Request.Builder().url(url)
                authPreferences.getAuthorizationHeader()?.let { authHeader ->
                    requestBuilder.addHeader("Authorization", authHeader)
                }

                coroutineContext.ensureActive()
                val call = client.newCall(requestBuilder.build())
                val pageResponse = executeCancellable(call)
                pageResponse.use { response ->
                    if (!response.isSuccessful) {
                        return@withContext Result.failure(
                            Exception("HTTP ${response.code}: ${response.message}")
                        )
                    }
                    if (lastPage == null) {
                        lastPage = parseLastPageFromLinkHeader(response.header("Link"))
                    }
                    val responseBody = response.body?.string()
                    if (responseBody.isNullOrBlank()) {
                        return@withContext Result.failure(Exception("Empty response body"))
                    }
                    val pageReleases = json.decodeFromString<List<GitHubRelease>>(responseBody)
                    if (pageReleases.isEmpty()) {
                        finished = true
                        return@use
                    }
                    for (release in pageReleases) {
                        if (seenIds.add(release.id)) {
                            releases.add(release)
                        }
                    }
                    if (pageReleases.size < perPage) {
                        finished = true
                        return@use
                    }
                    if (lastPage != null && page >= lastPage) {
                        finished = true
                        return@use
                    }
                }
                if (finished) break
                page += 1
            }
            releasesCache[cacheKey] =
                CachedReleases(releases = releases, cachedAt = SystemClock.elapsedRealtime())
            Result.success(releases)
        } catch (e: CancellationException) {
            // 协程取消必须继续向上传播，不能转成普通网络失败。
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 从 GitHub API 的 Link 响应头解析 rel="last" 指向的总页数。
     * 例如 `<https://api.github.com/repositories/123/releases?per_page=100&page=5>; rel="last"` -> 5。
     * 解析失败返回 null，调用方退回按返回不足一页判断。
     */
    private fun parseLastPageFromLinkHeader(linkHeader: String?): Int? {
        if (linkHeader.isNullOrBlank()) return null
        for (part in linkHeader.split(",")) {
            val segments = part.split(";")
            if (segments.size < 2) continue
            val urlPart = segments[0].trim()
            if (!urlPart.startsWith("<") || !urlPart.endsWith(">")) continue
            val rel = segments.drop(1).map { it.trim() }.firstOrNull { it.startsWith("rel=") } ?: continue
            if (rel.removePrefix("rel=").trim('"') != "last") continue
            val url = urlPart.substring(1, urlPart.length - 1)
            val pageParam = Regex("[?&]page=(\\d+)").find(url)?.groupValues?.get(1) ?: continue
            return pageParam.toIntOrNull()
        }
        return null
    }

    /**
     * 以可取消的方式执行 OkHttp 请求：协程取消时同步取消底层 Call，
     * 避免分页循环在用户取消更新后仍继续占用网络。
     */
    private suspend fun executeCancellable(call: Call): okhttp3.Response =
        suspendCancellableCoroutine { continuation ->
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isCancelled) return
                    continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: okhttp3.Response) {
                    // resume 的 onCancellation 参数会在协程取消导致值无法交付时回调，
                    // 此时必须关闭 Response，否则连接会泄漏（isCancelled 预检与 resume
                    // 之间仍存在竞态窗口，不能只靠预检）。
                    // resume 的 onCancellation 参数在协程取消导致值无法交付时回调，
                    // 此时必须关闭 Response，否则连接会泄漏（isCancelled 预检与
                    // resume 之间仍存在竞态窗口，不能只靠预检）。
                    continuation.resume(response) {
                        response.close()
                    }
                }
            })
            continuation.invokeOnCancellation {
                call.cancel()
            }
        }

    suspend fun createRepository(
        name: String,
        description: String? = null,
        homepage: String? = null,
        isPrivate: Boolean = false,
        autoInit: Boolean = false
    ): Result<GitHubRepository> = withContext(Dispatchers.IO) {
        try {
            val authHeader = authPreferences.getAuthorizationHeader()
                ?: return@withContext Result.failure(Exception("No access token available"))

            val requestPayload =
                CreateRepositoryRequest(
                    name = name,
                    description = description,
                    homepage = homepage,
                    `private` = isPrivate,
                    auto_init = autoInit
                )
            val requestBody =
                json.encodeToString(
                    CreateRepositoryRequest.serializer(),
                    requestPayload
                )

            val request =
                Request.Builder()
                    .url("$GITHUB_API_BASE/user/repos")
                    .post(requestBody.toRequestBody("application/json".toMediaType()))
                    .addHeader("Authorization", authHeader)
                    .addHeader("Accept", "application/vnd.github+json")
                    .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()

            if (response.isSuccessful && responseBody != null) {
                Result.success(json.decodeFromString(GitHubRepository.serializer(), responseBody))
            } else {
                Result.failure(buildHttpException(response.code, response.message, responseBody))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun createTextFile(
        owner: String,
        repo: String,
        path: String,
        message: String,
        textContent: String,
        branch: String? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val authHeader = authPreferences.getAuthorizationHeader()
                ?: return@withContext Result.failure(Exception("No access token available"))

            val existingFileSha =
                getRepositoryContentFile(
                    owner = owner,
                    repo = repo,
                    path = path
                ).fold(
                    onSuccess = { it?.sha },
                    onFailure = { error -> return@withContext Result.failure(error) }
                )

            val requestPayload =
                CreateRepositoryContentRequest(
                    message = message,
                    content = Base64.getEncoder().encodeToString(textContent.toByteArray(Charsets.UTF_8)),
                    branch = branch,
                    sha = existingFileSha
                )
            val requestBody =
                json.encodeToString(
                    CreateRepositoryContentRequest.serializer(),
                    requestPayload
                )

            val request =
                Request.Builder()
                    .url("$GITHUB_API_BASE/repos/$owner/$repo/contents/$path")
                    .put(requestBody.toRequestBody("application/json".toMediaType()))
                    .addHeader("Authorization", authHeader)
                    .addHeader("Accept", "application/vnd.github+json")
                    .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()

            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                Result.failure(buildHttpException(response.code, response.message, responseBody))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun getRepositoryContentFile(
        owner: String,
        repo: String,
        path: String
    ): Result<GitHubRepositoryContentFile?> = withContext(Dispatchers.IO) {
        try {
            val requestBuilder =
                Request.Builder()
                    .url("$GITHUB_API_BASE/repos/$owner/$repo/contents/$path")
                    .addHeader("Accept", "application/vnd.github+json")

            authPreferences.getAuthorizationHeader()?.let { authHeader ->
                requestBuilder.addHeader("Authorization", authHeader)
            }

            val response = client.newCall(requestBuilder.build()).execute()
            val responseBody = response.body?.string()

            if (response.isSuccessful && responseBody != null) {
                Result.success(
                    json.decodeFromString(
                        GitHubRepositoryContentFile.serializer(),
                        responseBody
                    )
                )
            } else if (response.code == 404) {
                Result.success(null)
            } else {
                Result.failure(buildHttpException(response.code, response.message, responseBody))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getReleaseByTag(
        owner: String,
        repo: String,
        tag: String
    ): Result<GitHubRelease> = withContext(Dispatchers.IO) {
        try {
            val requestBuilder =
                Request.Builder()
                    .url("$GITHUB_API_BASE/repos/$owner/$repo/releases/tags/$tag")
                    .addHeader("Accept", "application/vnd.github+json")

            authPreferences.getAuthorizationHeader()?.let { authHeader ->
                requestBuilder.addHeader("Authorization", authHeader)
            }

            val response = client.newCall(requestBuilder.build()).execute()
            val responseBody = response.body?.string()

            if (response.isSuccessful && responseBody != null) {
                Result.success(json.decodeFromString(GitHubRelease.serializer(), responseBody))
            } else {
                Result.failure(buildHttpException(response.code, response.message, responseBody))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun findReleaseByTag(
        owner: String,
        repo: String,
        tag: String
    ): Result<GitHubRelease?> = withContext(Dispatchers.IO) {
        getReleaseByTag(owner, repo, tag).fold(
            onSuccess = { Result.success(it) },
            onFailure = { error ->
                if (error.message?.contains("HTTP 404") == true) {
                    Result.success(null)
                } else {
                    Result.failure(error)
                }
            }
        )
    }

    suspend fun createRelease(
        owner: String,
        repo: String,
        tagName: String,
        name: String? = null,
        body: String? = null,
        draft: Boolean = false,
        prerelease: Boolean = false
    ): Result<GitHubRelease> = withContext(Dispatchers.IO) {
        try {
            val authHeader = authPreferences.getAuthorizationHeader()
                ?: return@withContext Result.failure(Exception("No access token available"))

            val payload =
                CreateReleaseRequest(
                    tag_name = tagName,
                    name = name,
                    body = body,
                    draft = draft,
                    prerelease = prerelease
                )
            val requestBody =
                json.encodeToString(
                    CreateReleaseRequest.serializer(),
                    payload
                )

            val request =
                Request.Builder()
                    .url("$GITHUB_API_BASE/repos/$owner/$repo/releases")
                    .post(requestBody.toRequestBody("application/json".toMediaType()))
                    .addHeader("Authorization", authHeader)
                    .addHeader("Accept", "application/vnd.github+json")
                    .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()

            if (response.isSuccessful && responseBody != null) {
                Result.success(json.decodeFromString(GitHubRelease.serializer(), responseBody))
            } else {
                Result.failure(buildHttpException(response.code, response.message, responseBody))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateRelease(
        owner: String,
        repo: String,
        releaseId: Long,
        tagName: String? = null,
        name: String? = null,
        body: String? = null,
        draft: Boolean? = null,
        prerelease: Boolean? = null
    ): Result<GitHubRelease> = withContext(Dispatchers.IO) {
        try {
            val authHeader = authPreferences.getAuthorizationHeader()
                ?: return@withContext Result.failure(Exception("No access token available"))

            val payload =
                UpdateReleaseRequest(
                    tag_name = tagName,
                    name = name,
                    body = body,
                    draft = draft,
                    prerelease = prerelease
                )
            val requestBody =
                json.encodeToString(
                    UpdateReleaseRequest.serializer(),
                    payload
                )

            val request =
                Request.Builder()
                    .url("$GITHUB_API_BASE/repos/$owner/$repo/releases/$releaseId")
                    .patch(requestBody.toRequestBody("application/json".toMediaType()))
                    .addHeader("Authorization", authHeader)
                    .addHeader("Accept", "application/vnd.github+json")
                    .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()

            if (response.isSuccessful && responseBody != null) {
                Result.success(json.decodeFromString(GitHubRelease.serializer(), responseBody))
            } else {
                Result.failure(buildHttpException(response.code, response.message, responseBody))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun downloadReleaseAsset(downloadUrl: String): Result<ByteArray> = withContext(Dispatchers.IO) {
        try {
            val requestBuilder = Request.Builder().url(downloadUrl)
            authPreferences.getAuthorizationHeader()?.let { authHeader ->
                requestBuilder.addHeader("Authorization", authHeader)
            }

            client.newCall(requestBuilder.build()).execute().use { response ->
                val responseBody = response.body
                if (response.isSuccessful && responseBody != null) {
                    Result.success(responseBody.bytes())
                } else {
                    Result.failure(Exception("HTTP ${response.code}: ${response.message}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteRelease(
        owner: String,
        repo: String,
        releaseId: Long
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val authHeader = authPreferences.getAuthorizationHeader()
                ?: return@withContext Result.failure(Exception("No access token available"))

            val request =
                Request.Builder()
                    .url("$GITHUB_API_BASE/repos/$owner/$repo/releases/$releaseId")
                    .delete()
                    .addHeader("Authorization", authHeader)
                    .addHeader("Accept", "application/vnd.github+json")
                    .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()

            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                Result.failure(buildHttpException(response.code, response.message, responseBody))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteReleaseAsset(
        owner: String,
        repo: String,
        assetId: Long
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val authHeader = authPreferences.getAuthorizationHeader()
                ?: return@withContext Result.failure(Exception("No access token available"))

            val request =
                Request.Builder()
                    .url("$GITHUB_API_BASE/repos/$owner/$repo/releases/assets/$assetId")
                    .delete()
                    .addHeader("Authorization", authHeader)
                    .addHeader("Accept", "application/vnd.github+json")
                    .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()

            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                Result.failure(buildHttpException(response.code, response.message, responseBody))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun uploadReleaseAsset(
        owner: String,
        repo: String,
        releaseId: Long,
        assetName: String,
        contentType: String,
        content: ByteArray
    ): Result<GitHubReleaseAsset> = withContext(Dispatchers.IO) {
        try {
            val authHeader = authPreferences.getAuthorizationHeader()
                ?: return@withContext Result.failure(Exception("No access token available"))

            val uploadUrl =
                HttpUrl.Builder()
                    .scheme("https")
                    .host("uploads.github.com")
                    .addPathSegment("repos")
                    .addPathSegment(owner)
                    .addPathSegment(repo)
                    .addPathSegment("releases")
                    .addPathSegment(releaseId.toString())
                    .addPathSegment("assets")
                    .addQueryParameter("name", assetName)
                    .build()

            val request =
                Request.Builder()
                    .url(uploadUrl)
                    .post(content.toRequestBody(contentType.toMediaType()))
                    .addHeader("Authorization", authHeader)
                    .addHeader("Accept", "application/vnd.github+json")
                    .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()

            if (response.isSuccessful && responseBody != null) {
                Result.success(json.decodeFromString(GitHubReleaseAsset.serializer(), responseBody))
            } else {
                Result.failure(buildHttpException(response.code, response.message, responseBody))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun buildHttpException(
        code: Int,
        message: String,
        responseBody: String?
    ): Exception {
        val body = responseBody?.takeIf { it.isNotBlank() }
        return Exception(
            if (body != null) {
                "HTTP $code: $message\n$body"
            } else {
                "HTTP $code: $message"
            }
        )
    }
}

