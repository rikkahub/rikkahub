package me.rerere.ai.provider

/**
 * 为 [ProviderSetting.Account] 提供可用的凭据。
 *
 * 实现方（app 层）负责按需刷新 token 并把新凭据写回设置；Provider 只管拿来用。
 */
fun interface AccountCredentialProvider {
    /**
     * 返回 [setting] 当前可用的凭据，快过期时先刷新。
     *
     * [rejectedAccessToken] 是刚被服务端以 401 拒绝的 token：存着的还是它就强制刷新，
     * 已经被并发的请求刷新过了就直接返回新的，避免重复使用同一个 refresh token。
     *
     * 未登录或刷新失败时抛出异常。
     */
    suspend fun getCredential(
        setting: ProviderSetting.Account,
        rejectedAccessToken: String?,
    ): OAuthCredential
}
