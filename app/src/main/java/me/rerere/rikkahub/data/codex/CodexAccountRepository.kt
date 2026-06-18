package me.rerere.rikkahub.data.codex

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.common.http.await
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

class CodexAccountRepository internal constructor(
    private val store: CodexCredentialStore,
    private val client: OkHttpClient,
    private val json: Json,
) {
    private val mutex = Mutex()
    private var state = store.read().let { stored ->
        stored.copy(
            accounts = stored.accounts.map { account ->
                if (
                    account.tokenStatus != CodexTokenStatus.INVALID &&
                    account.expiresAt <= System.currentTimeMillis()
                ) {
                    account.copy(tokenStatus = CodexTokenStatus.EXPIRED)
                } else {
                    account
                }
            }
        )
    }
    private val _accounts = MutableStateFlow(state.accounts)
    val accounts: StateFlow<List<CodexAccount>> = _accounts.asStateFlow()

    suspend fun saveLogin(tokenJson: String): CodexAccount = mutex.withLock {
        val token = json.parseToJsonElement(tokenJson).jsonObject
        val identity = parseCodexIdentity(
            idToken = token["id_token"]?.jsonPrimitive?.contentOrNull
                ?: error("Missing ID token"),
            json = json,
        )
        val now = System.currentTimeMillis()
        val existing = state.accounts.firstOrNull {
            it.chatgptAccountId == identity.accountId &&
                if (it.userId.isNotBlank() && identity.userId.isNotBlank()) {
                    it.userId == identity.userId
                } else {
                    it.email == identity.email
                }
        }
        val account = CodexAccount(
            id = existing?.id ?: "${identity.userId.ifBlank { identity.email }}:${identity.accountId}",
            userId = identity.userId,
            name = identity.name,
            email = identity.email,
            chatgptAccountId = identity.accountId,
            accessToken = token["access_token"]?.jsonPrimitive?.contentOrNull
                ?: error("Missing access token"),
            refreshToken = token["refresh_token"]?.jsonPrimitive?.contentOrNull
                ?: existing?.refreshToken
                ?: error("Missing refresh token"),
            expiresAt = now + (
                token["expires_in"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 3600L
                ) * 1000,
            enabled = existing?.enabled ?: true,
            tokenStatus = CodexTokenStatus.AVAILABLE,
            usage = existing?.usage,
        )
        updateState(
            state.copy(
                accounts = state.accounts.filterNot { it.id == account.id } + account
            )
        )
        account
    }

    suspend fun acquireAccount(): CodexAccount = mutex.withLock {
        if (state.accounts.isEmpty()) error("No Codex account is signed in")
        repeat(state.accounts.size) {
            val index = selectCodexAccountIndex(
                accounts = state.accounts,
                startIndex = state.nextAccountIndex,
            ) ?: error("No available Codex account")
            val candidate = state.accounts[index]
            if (!candidate.isAvailable()) return@repeat
            updateState(state.copy(nextAccountIndex = (index + 1) % state.accounts.size))
            val fresh = runCatching { ensureFreshLocked(candidate) }.getOrNull() ?: return@repeat
            return fresh
        }
        error("No available Codex account")
    }

    suspend fun updateUsage(accountId: String, usage: CodexUsageSnapshot) = mutex.withLock {
        replaceAccount(accountId) { it.copy(usage = usage) }
    }

    suspend fun setEnabled(accountId: String, enabled: Boolean) = mutex.withLock {
        replaceAccount(accountId) { it.copy(enabled = enabled) }
    }

    suspend fun markInvalid(accountId: String) = mutex.withLock {
        replaceAccount(accountId) { it.copy(tokenStatus = CodexTokenStatus.INVALID) }
    }

    suspend fun delete(accountId: String) = mutex.withLock {
        updateState(
            state.copy(
                accounts = state.accounts.filterNot { it.id == accountId },
                nextAccountIndex = 0,
            )
        )
    }

    suspend fun refreshAccount(accountId: String): CodexAccount = mutex.withLock {
        val account = state.accounts.firstOrNull { it.id == accountId }
            ?: error("Codex account not found")
        val fresh = ensureFreshLocked(account)
        fetchUsageLocked(fresh)
    }

    suspend fun refreshAll() {
        accounts.value.forEach { account ->
            runCatching { refreshAccount(account.id) }
        }
    }

    private suspend fun ensureFreshLocked(
        account: CodexAccount,
        force: Boolean = false,
    ): CodexAccount {
        if (!force && account.expiresAt > System.currentTimeMillis() + REFRESH_MARGIN_MS) {
            return account
        }
        val response = withContext(Dispatchers.IO) {
            val body = FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("client_id", CodexOAuthManager.CLIENT_ID)
                .add("refresh_token", account.refreshToken)
                .add("scope", CodexOAuthManager.REFRESH_SCOPES)
                .build()
            client.newCall(
                Request.Builder()
                    .url(CodexOAuthManager.TOKEN_URL)
                    .post(body)
                    .build()
            ).await()
        }
        val responseBody = response.body.string()
        if (!response.isSuccessful) {
            if (isCodexRefreshAuthenticationFailure(response.code, responseBody, json)) {
                replaceAccount(account.id) { it.copy(tokenStatus = CodexTokenStatus.INVALID) }
            }
            error("Token refresh failed: ${response.code}")
        }
        val token = json.parseToJsonElement(responseBody).jsonObject
        val updated = account.copy(
            accessToken = token["access_token"]?.jsonPrimitive?.contentOrNull
                ?: error("Missing refreshed access token"),
            refreshToken = token["refresh_token"]?.jsonPrimitive?.contentOrNull
                ?: account.refreshToken,
            expiresAt = System.currentTimeMillis() + (
                token["expires_in"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 3600L
                ) * 1000,
            tokenStatus = CodexTokenStatus.AVAILABLE,
        )
        replaceAccount(account.id) { updated }
        return updated
    }

    private suspend fun fetchUsageLocked(account: CodexAccount): CodexAccount {
        val response = withContext(Dispatchers.IO) {
            client.newCall(
                Request.Builder()
                    .url("$CODEX_BASE_URL/wham/usage")
                    .codexHeaders(account)
                    .get()
                    .build()
            ).await()
        }
        if (!response.isSuccessful) {
            if (response.code == 401) {
                replaceAccount(account.id) { it.copy(tokenStatus = CodexTokenStatus.INVALID) }
            }
            error("Failed to fetch Codex usage: ${response.code}")
        }
        val usage = parseCodexUsage(json.parseToJsonElement(response.body.string()).jsonObject)
        val updated = account.copy(
            tokenStatus = CodexTokenStatus.AVAILABLE,
            usage = usage,
        )
        replaceAccount(account.id) { updated }
        return updated
    }

    private fun Request.Builder.codexHeaders(account: CodexAccount): Request.Builder {
        return addHeader("Authorization", "Bearer ${account.accessToken}")
            .addHeader("ChatGPT-Account-Id", account.chatgptAccountId)
            .addHeader("originator", "codex_cli_rs")
            .addHeader("Accept", "application/json")
    }

    private fun replaceAccount(
        accountId: String,
        transform: (CodexAccount) -> CodexAccount,
    ) {
        updateState(
            state.copy(
                accounts = state.accounts.map {
                    if (it.id == accountId) transform(it) else it
                }
            )
        )
    }

    private fun updateState(newState: CodexAccountState) {
        state = newState
        store.write(newState)
        _accounts.value = newState.accounts
    }

    companion object {
        const val CODEX_BASE_URL = "https://chatgpt.com/backend-api"
        private const val REFRESH_MARGIN_MS = 30_000L
    }
}

internal fun isCodexRefreshAuthenticationFailure(
    statusCode: Int,
    responseBody: String,
    json: Json,
): Boolean {
    if (statusCode == 401) return true
    if (statusCode != 400) return false
    val errorCode = runCatching {
        json.parseToJsonElement(responseBody).jsonObject["error"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()
    return errorCode == "invalid_grant" || errorCode == "invalid_token"
}

internal fun selectCodexAccountIndex(
    accounts: List<CodexAccount>,
    startIndex: Int,
    nowMillis: Long = System.currentTimeMillis(),
): Int? {
    if (accounts.isEmpty()) return null
    repeat(accounts.size) { offset ->
        val index = (startIndex + offset).mod(accounts.size)
        if (accounts[index].isAvailable(nowMillis)) return index
    }
    return null
}
