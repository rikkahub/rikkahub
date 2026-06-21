package me.rerere.rikkahub.data.catalog

/**
 * The curated provider catalog: locally-owned templates the user browses and adds (only the ones they
 * pick). Once added, models are fetched from the PROVIDER's own /models endpoint — not models.dev — so a
 * wrong API key surfaces as a real failure instead of a misleading list.
 *
 * Curation rationale: only providers rikkahub can drive with a single API key over an existing wire
 * adapter (OpenAI-compatible / Google / Anthropic). Special-auth providers (Vertex service-account,
 * Azure, Bedrock SigV4) are intentionally excluded — they stay on the manual-config path. The set
 * evolves the previous hardcoded DEFAULT_PROVIDERS into a browseable catalog (RikkaHub gateway dropped).
 */
object ProviderTemplates {

    /** Decision B: Anthropic (Claude) is the default provider seeded + offered first. */
    val DEFAULT: ProviderTemplate = ProviderTemplate(
        modelsDevId = "anthropic",
        displayName = "Anthropic",
        wire = ProviderWire.ANTHROPIC,
        docUrl = "https://docs.anthropic.com",
    )

    val ALL: List<ProviderTemplate> = listOf(
        DEFAULT,
        ProviderTemplate("openai", "OpenAI", ProviderWire.OPENAI, "https://api.openai.com/v1", "https://platform.openai.com/docs"),
        ProviderTemplate("google", "Gemini", ProviderWire.GOOGLE, docUrl = "https://ai.google.dev/gemini-api/docs"),
        ProviderTemplate("deepseek", "DeepSeek", ProviderWire.OPENAI, "https://api.deepseek.com/v1"),
        ProviderTemplate("openrouter", "OpenRouter", ProviderWire.OPENAI, "https://openrouter.ai/api/v1"),
        ProviderTemplate("xai", "xAI", ProviderWire.OPENAI, "https://api.x.ai/v1"),
        ProviderTemplate("mistral", "Mistral", ProviderWire.OPENAI, "https://api.mistral.ai/v1"),
        ProviderTemplate("groq", "Groq", ProviderWire.OPENAI, "https://api.groq.com/openai/v1"),
        ProviderTemplate("siliconflow", "SiliconFlow", ProviderWire.OPENAI, "https://api.siliconflow.cn/v1"),
        ProviderTemplate("aihubmix", "AiHubMix", ProviderWire.OPENAI, "https://aihubmix.com/v1"),
        ProviderTemplate("alibaba", "Alibaba Bailian", ProviderWire.OPENAI, "https://dashscope.aliyuncs.com/compatible-mode/v1"),
        ProviderTemplate("moonshotai", "Moonshot", ProviderWire.OPENAI, "https://api.moonshot.cn/v1"),
        ProviderTemplate("zhipuai", "Zhipu AI", ProviderWire.OPENAI, "https://open.bigmodel.cn/api/paas/v4"),
        ProviderTemplate("stepfun", "StepFun", ProviderWire.OPENAI, "https://api.stepfun.com/v1"),
        ProviderTemplate("xiaomi", "Xiaomi", ProviderWire.OPENAI, "https://api.xiaomimimo.com/v1", "https://platform.xiaomimimo.com/#/docs"),
        ProviderTemplate("302ai", "302.AI", ProviderWire.OPENAI, "https://api.302.ai/v1"),
        ProviderTemplate("tencent-tokenhub", "Tencent Hunyuan", ProviderWire.OPENAI, "https://api.hunyuan.cloud.tencent.com/v1"),
        ProviderTemplate("vercel", "Vercel AI Gateway", ProviderWire.OPENAI, "https://ai-gateway.vercel.sh/v1"),
        // Not on models.dev (blank modelsDevId) — models resolve via the provider's own /models API
        // + the ModelRegistry/models.dev bare-id gap-fill.
        ProviderTemplate("", "Volcengine Ark", ProviderWire.OPENAI, "https://ark.cn-beijing.volces.com/api/v3"),
        ProviderTemplate("", "TokenPony", ProviderWire.OPENAI, "https://api.tokenpony.cn/v1"),
    )
}
