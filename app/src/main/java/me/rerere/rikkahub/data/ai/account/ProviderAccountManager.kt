package me.rerere.rikkahub.data.ai.account

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.ai.provider.AccountCredentialProvider
import me.rerere.ai.provider.OAuthCredential
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.oauth.CustomTabsOAuthAuthorizationLauncher
import me.rerere.oauth.OAuthHttpClient
import me.rerere.oauth.OAuthLoopbackCallbackServer
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.SettingsStore
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

private const val TAG = "ProviderAccountManager"

// 与 Codex CLI 一致：access token 过期前 5 分钟内就刷新
private const val TOKEN_REFRESH_LEEWAY_MS = 5 * 60_000L
private val AUTHORIZATION_TIMEOUT = 5.minutes

sealed interface AccountLoginStatus {
    data object Authorizing : AccountLoginStatus
    data class Failed(val message: String) : AccountLoginStatus
}

/**
 * 管理账号型提供商（[ProviderSetting.Account]）的登录、token 刷新和凭据持久化。
 *
 * 凭据只由这里写回设置，界面编辑提供商时应保留设置里的最新凭据，见 [preserveCredential]。
 */
class ProviderAccountManager(
    private val settingsStore: SettingsStore,
    private val appScope: AppScope,
    client: OkHttpClient,
    // ProviderManager 依赖本类提供凭据，这里延迟获取以避开循环依赖
    private val providerManager: Lazy<ProviderManager>,
) : AccountCredentialProvider {
    // 走应用的 OkHttpClient，授权和刷新请求也能用上设置里的代理
    private val oauthClient = OAuthHttpClient(client)
    private val chatGPTCallbackServer = OAuthLoopbackCallbackServer(
        port = ChatGPTAuth.CALLBACK_PORT,
        callbackPath = ChatGPTAuth.CALLBACK_PATH,
        redirectHost = ChatGPTAuth.CALLBACK_HOST,
    )
    private val loginJobs = ConcurrentHashMap<Uuid, Job>()
    private val refreshLocks = ConcurrentHashMap<Uuid, Mutex>()

    private val _loginStatus = MutableStateFlow<Map<Uuid, AccountLoginStatus>>(emptyMap())
    val loginStatus: StateFlow<Map<Uuid, AccountLoginStatus>> = _loginStatus.asStateFlow()

    fun startLogin(providerId: Uuid, context: Context) {
        val provider = findAccount(providerId) ?: return
        loginJobs.remove(providerId)?.cancel()
        setStatus(providerId, AccountLoginStatus.Authorizing)
        val job = appScope.launch {
            try {
                val credential = when (provider) {
                    is ProviderSetting.ChatGPT -> loginChatGPT(context.applicationContext)
                }
                persistCredential(providerId, credential)
                setStatus(providerId, null)
                syncModelsIfEmpty(providerId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Login failed for ${provider.name}", e)
                setStatus(providerId, AccountLoginStatus.Failed(e.message ?: e.javaClass.simpleName))
            }
        }
        loginJobs[providerId] = job
        job.invokeOnCompletion { loginJobs.remove(providerId, job) }
    }

    fun cancelLogin(providerId: Uuid) {
        loginJobs.remove(providerId)?.cancel()
        setStatus(providerId, null)
    }

    suspend fun logout(providerId: Uuid) {
        cancelLogin(providerId)
        persistCredential(providerId, null)
    }

    /**
     * 在 [appScope] 里执行，调用方被取消（停止生成、关掉额度界面）时打断不了已经发出的刷新：
     * 服务端一收到请求就会轮换 refresh token，新的没存下来就只能重新登录。
     */
    override suspend fun getCredential(
        setting: ProviderSetting.Account,
        rejectedAccessToken: String?,
    ): OAuthCredential = appScope.async(Dispatchers.IO) {
        refreshIfNeeded(setting, rejectedAccessToken)
    }.await()

    /**
     * 按提供商串行刷新。拿到锁后重新读设置：并发请求可能已经刷新过，refresh token 只能用一次。
     */
    private suspend fun refreshIfNeeded(
        setting: ProviderSetting.Account,
        rejectedAccessToken: String?,
    ): OAuthCredential {
        val lock = refreshLocks.computeIfAbsent(setting.id) { Mutex() }
        return lock.withLock {
            val latest = findAccount(setting.id) ?: setting
            val credential = latest.credential ?: error("${latest.name}: not signed in")
            val rejected = rejectedAccessToken != null && rejectedAccessToken == credential.accessToken
            val expiring = credential.expiresAt > 0 &&
                System.currentTimeMillis() >= credential.expiresAt - TOKEN_REFRESH_LEEWAY_MS
            if (!rejected && !expiring) return@withLock credential

            val refreshToken = credential.refreshToken
                ?: error("${latest.name}: session expired, please sign in again")
            val refreshed = try {
                when (latest) {
                    is ProviderSetting.ChatGPT -> ChatGPTAuth.toCredential(
                        token = oauthClient.refreshToken(ChatGPTAuth.refreshRequest(refreshToken)),
                        previous = credential,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw IllegalStateException("${latest.name}: failed to refresh sign-in, please sign in again", e)
            }
            persistCredential(latest.id, refreshed)
            refreshed
        }
    }

    private suspend fun loginChatGPT(context: Context): OAuthCredential = withContext(Dispatchers.IO) {
        val pkce = oauthClient.generatePkce()
        val state = oauthClient.generateState()
        val session = chatGPTCallbackServer.openSession(context, state)
        try {
            val authorizationUrl = oauthClient.buildAuthorizationUrl(
                ChatGPTAuth.authorizationRequest(pkce = pkce, state = state, redirectUri = session.redirectUri)
            )
            withContext(Dispatchers.Main) {
                CustomTabsOAuthAuthorizationLauncher.launch(context, authorizationUrl)
            }
            val callback = session.awaitCallback(AUTHORIZATION_TIMEOUT) ?: error("Authorization timed out")
            callback.error?.let { code ->
                error(listOfNotNull(code, callback.errorDescription).joinToString(": "))
            }
            val code = callback.code ?: error("No authorization code returned")
            val token = oauthClient.exchangeAuthorizationCode(
                OAuthHttpClient.AuthorizationCodeTokenRequest(
                    tokenEndpoint = ChatGPTAuth.TOKEN_ENDPOINT,
                    clientId = ChatGPTAuth.CLIENT_ID,
                    code = code,
                    codeVerifier = pkce.verifier,
                    redirectUri = session.redirectUri,
                )
            )
            ChatGPTAuth.toCredential(token, previous = null)
        } finally {
            withContext(NonCancellable) { session.close() }
        }
    }

    // 刚登录、还没有模型时，把账号可用的模型直接加进来
    private suspend fun syncModelsIfEmpty(providerId: Uuid) {
        val provider = findAccount(providerId)?.takeIf { it.models.isEmpty() } ?: return
        val models = runCatching {
            providerManager.value.getProviderByType(provider).listModels(provider)
        }.getOrElse {
            Log.w(TAG, "Failed to fetch models for ${provider.name}", it)
            return
        }
        settingsStore.update { old ->
            old.copy(
                providers = old.providers.map { item ->
                    if (item.id == providerId && item.models.isEmpty()) item.copyProvider(models = models) else item
                }
            )
        }
    }

    private suspend fun persistCredential(providerId: Uuid, credential: OAuthCredential?) {
        settingsStore.update { old ->
            old.copy(
                providers = old.providers.map { provider ->
                    if (provider.id == providerId && provider is ProviderSetting.Account) {
                        provider.withCredential(credential)
                    } else {
                        provider
                    }
                }
            )
        }
    }

    private fun findAccount(providerId: Uuid): ProviderSetting.Account? =
        settingsStore.settingsFlow.value.providers
            .find { it.id == providerId } as? ProviderSetting.Account

    private fun setStatus(providerId: Uuid, status: AccountLoginStatus?) {
        _loginStatus.update { if (status == null) it - providerId else it + (providerId to status) }
    }
}

/**
 * 界面整份写回提供商时用：凭据只能由 [ProviderAccountManager] 修改，
 * 编辑期间后台可能已经刷新过 token，旧凭据写回会把轮换后的 refresh token 覆盖掉。
 */
fun ProviderSetting.preserveCredential(latest: ProviderSetting): ProviderSetting =
    if (this is ProviderSetting.Account && latest is ProviderSetting.Account) {
        withCredential(latest.credential)
    } else {
        this
    }
