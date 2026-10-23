package me.rerere.ai.registry

import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.ModelAbility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

class ModelsDevCatalogTest {
    private val fixture = """
        {
          "anthropic": {
            "id": "anthropic",
            "name": "Anthropic",
            "models": {
              "claude-sonnet-4-5": {
                "id": "claude-sonnet-4-5",
                "name": "Claude Sonnet 4.5",
                "attachment": true,
                "reasoning": true,
                "tool_call": true,
                "modalities": { "input": ["text", "image", "pdf"], "output": ["text"] },
                "limit": { "context": 200000, "output": 64000 },
                "cost": { "input": 3, "output": 15 }
              }
            }
          },
          "openrouter": {
            "models": {
              "anthropic/claude-sonnet-4.5": {
                "id": "anthropic/claude-sonnet-4.5",
                "reasoning": true,
                "tool_call": true,
                "modalities": { "input": ["text", "image"], "output": ["text"] },
                "limit": { "context": 1000000 },
                "canonical_model_id": "anthropic/claude-sonnet-4-5"
              },
              "openai/gpt-oss-120b": {
                "id": "openai/gpt-oss-120b",
                "reasoning": true,
                "tool_call": true,
                "modalities": { "input": ["text"], "output": ["text"] },
                "limit": { "context": 131072 }
              },
              "moonshotai/kimi-k3": {
                "id": "moonshotai/kimi-k3",
                "reasoning": true,
                "tool_call": true,
                "modalities": { "input": ["text", "image"], "output": ["text"] }
              }
            }
          },
          "siliconflow": {
            "models": {
              "Pro/deepseek-ai/DeepSeek-V3": {
                "id": "Pro/deepseek-ai/DeepSeek-V3",
                "reasoning": false,
                "tool_call": true,
                "modalities": { "input": ["text"], "output": ["text"] },
                "limit": { "context": 164000 }
              },
              "kimi-k3": {
                "id": "kimi-k3",
                "reasoning": true,
                "tool_call": null,
                "modalities": { "input": ["text"], "output": ["text"] }
              }
            }
          },
          "alibaba": {
            "models": {
              "qwen3-8b": {
                "id": "qwen3-8b",
                "reasoning": true,
                "tool_call": true,
                "modalities": { "input": ["text"], "output": ["text"] }
              },
              "kimi-k3": {
                "id": "kimi-k3",
                "reasoning": true,
                "tool_call": true,
                "modalities": { "input": ["text", "image"], "output": ["text"] }
              }
            }
          },
          "openai": {
            "models": {
              "o3-mini": {
                "id": "o3-mini",
                "reasoning": true,
                "tool_call": true,
                "modalities": { "input": ["text"], "output": ["text"] },
                "limit": { "context": 200000 }
              }
            }
          },
          "google": {
            "models": {
              "gemini-2.5-flash-image": {
                "id": "gemini-2.5-flash-image",
                "modalities": { "input": ["text", "image"], "output": ["text", "image"] }
              }
            }
          }
        }
    """.trimIndent()

    private val catalog = ModelsDevCatalog.decode(fixture.byteInputStream())

    private val visionInput = setOf(Modality.TEXT, Modality.IMAGE)
    private val textOnly = setOf(Modality.TEXT)
    private val toolReasoning = setOf(ModelAbility.TOOL, ModelAbility.REASONING)

    @Test
    fun findByExactIdAndName() {
        val sonnet = catalog.find("claude-sonnet-4-5")!!
        assertEquals(visionInput, sonnet.inputModalities)
        assertEquals(toolReasoning, sonnet.abilities)

        // 带前缀的完整 id、大小写不同、只给最后一段名称都能查到
        assertEquals(164000, catalog.find("Pro/deepseek-ai/DeepSeek-V3")!!.contextLength)
        assertEquals(164000, catalog.find("deepseek-v3")!!.contextLength)
        assertEquals(setOf(ModelAbility.TOOL), catalog.find("deepseek-v3")!!.abilities)
        assertEquals(textOnly, catalog.find("qwen3-8b")!!.inputModalities)
        assertEquals(visionInput, catalog.find("gemini-2.5-flash-image")!!.outputModalities)
        assertEquals(emptySet<ModelAbility>(), catalog.find("gemini-2.5-flash-image")!!.abilities)
    }

    @Test
    fun stripVariantSuffix() {
        assertEquals(131072, catalog.find("openai/gpt-oss-120b:free")!!.contextLength)
        assertEquals(131072, catalog.find("gpt-oss-120b:free")!!.contextLength)
    }

    @Test
    fun canonicalIdJoinsVotes() {
        // openrouter 的记录 canonical 指向 claude-sonnet-4-5，两票的上下文长度持平时取较大值
        assertEquals(1_000_000, catalog.find("claude-sonnet-4-5")!!.contextLength)
        assertEquals(1_000_000, catalog.find("anthropic/claude-sonnet-4-5")!!.contextLength)
    }

    @Test
    fun mergeByMajority() {
        // 三家里两家标了图片输入；null 的 tool_call 按 false 计，2:1 仍算支持
        val kimi = catalog.find("kimi-k3")!!
        assertEquals(visionInput, kimi.inputModalities)
        assertEquals(toolReasoning, kimi.abilities)
        assertNull(kimi.contextLength)
    }

    @Test
    fun unknownModel() {
        assertNull(catalog.find("unknown-model-xyz"))
        assertNull(catalog.find(""))
    }

    @Test
    fun encodeRoundTrip() {
        val output = ByteArrayOutputStream()
        catalog.encodeTo(output)
        val encoded = output.toString(Charsets.UTF_8)
        assertFalse(encoded.contains("cost"))
        assertFalse(encoded.contains("pdf"))

        val decoded = ModelsDevCatalog.decode(encoded.byteInputStream())
        assertEquals(catalog.size, decoded.size)
        assertEquals(visionInput, decoded.find("kimi-k3")!!.inputModalities)
        assertEquals(1_000_000, decoded.find("claude-sonnet-4-5")!!.contextLength)
        assertEquals(visionInput, decoded.find("gemini-2.5-flash-image")!!.outputModalities)
    }

    @Test
    fun registryPrefersCatalogAndFallsBackToTokens() {
        try {
            ModelRegistry.installCatalog(catalog)
            // token 规则把 o3 系列都当成支持图片输入，目录数据里 o3-mini 不支持
            assertEquals(listOf(Modality.TEXT), ModelRegistry.MODEL_INPUT_MODALITIES.getData("o3-mini"))
            assertEquals(1_000_000, ModelRegistry.MODEL_CONTEXT_LENGTH.getData("claude-sonnet-4-5"))
            assertEquals(
                listOf(Modality.TEXT, Modality.IMAGE),
                ModelRegistry.MODEL_OUTPUT_MODALITIES.getData("gemini-2.5-flash-image")
            )
            assertEquals(listOf(ModelAbility.TOOL), ModelRegistry.MODEL_ABILITIES.getData("deepseek-v3"))
            // 目录里没有的模型走 token 规则
            assertEquals(
                listOf(Modality.TEXT, Modality.IMAGE),
                ModelRegistry.MODEL_INPUT_MODALITIES.getData("muse-spark")
            )
            assertEquals(
                listOf(ModelAbility.TOOL, ModelAbility.REASONING),
                ModelRegistry.MODEL_ABILITIES.getData("muse-spark")
            )
            assertEquals(1_000_000, ModelRegistry.MODEL_CONTEXT_LENGTH.getData("deepseek-v4-flash"))
            // 目录里有但缺上下文长度时，上下文长度回落到 token 规则
            assertNull(ModelRegistry.MODEL_CONTEXT_LENGTH.getData("kimi-k3"))
        } finally {
            ModelRegistry.installCatalog(null)
        }
        assertEquals(
            listOf(Modality.TEXT, Modality.IMAGE),
            ModelRegistry.MODEL_INPUT_MODALITIES.getData("o3-mini")
        )
    }
}
