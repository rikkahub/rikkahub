"""Integration tests for AI translation quality.

These tests call the real API and verify translation results meet expectations.
Run with: uv run pytest tests/test_translator.py -v
"""

import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).parent.parent / "src"))

from config import Config
from services.translator import AITranslator

CONFIG_PATH = Path(__file__).parent.parent / "config.yml"


@pytest.fixture(scope="module")
def translator():
    config = Config.load(CONFIG_PATH)
    return AITranslator(config)


@pytest.mark.asyncio
async def test_prompt_translates_to_tishici(translator):
    """'prompt' 在 AI/LLM 上下文中应翻译为'提示词'而非'提示'。"""
    result = await translator.translate_batch(
        {"system_prompt": "System Prompt", "prompt_template": "Prompt Template"},
        "Chinese (Simplified)",
    )
    assert "system_prompt" in result
    assert "prompt_template" in result
    assert "提示词" in result["system_prompt"], (
        f"Expected '提示词' in system_prompt translation, got: {result['system_prompt']!r}"
    )
    assert "提示词" in result["prompt_template"], (
        f"Expected '提示词' in prompt_template translation, got: {result['prompt_template']!r}"
    )


@pytest.mark.asyncio
async def test_placeholder_preserved(translator):
    """Android 占位符 %1$s / %s 不应被翻译或修改。"""
    result = await translator.translate_batch(
        {"welcome_user": "Welcome, %1$s!", "items_count": "You have %d items"},
        "Chinese (Simplified)",
    )
    assert "%1$s" in result["welcome_user"], (
        f"Placeholder %1$s missing in: {result['welcome_user']!r}"
    )
    assert "%d" in result["items_count"], (
        f"Placeholder %d missing in: {result['items_count']!r}"
    )


@pytest.mark.asyncio
async def test_brand_name_not_translated(translator):
    """品牌名 'OpenAI'、'Anthropic'、'Google' 不应被翻译。"""
    result = await translator.translate_batch(
        {"provider_openai": "OpenAI Provider", "provider_anthropic": "Anthropic Provider"},
        "Chinese (Simplified)",
    )
    assert "OpenAI" in result["provider_openai"], (
        f"Brand name 'OpenAI' should be preserved, got: {result['provider_openai']!r}"
    )
    assert "Anthropic" in result["provider_anthropic"], (
        f"Brand name 'Anthropic' should be preserved, got: {result['provider_anthropic']!r}"
    )


@pytest.mark.asyncio
async def test_token_not_over_translated(translator):
    """'Token' 在 LLM 上下文中应保留英文或译为 'Token'，不应译为'令牌'等无关词。"""
    result = await translator.translate_batch(
        {"token_usage": "Token Usage", "max_tokens": "Max Tokens"},
        "Chinese (Simplified)",
    )
    for key in ("token_usage", "max_tokens"):
        val = result[key].lower()
        assert "token" in val, (
            f"Expected 'token' to remain in {key!r} translation, got: {result[key]!r}"
        )


@pytest.mark.asyncio
async def test_keys_preserved_in_response(translator):
    """返回 JSON 的 key 必须与输入一一对应。"""
    entries = {
        "setting_title": "Settings",
        "cancel_button": "Cancel",
        "confirm_button": "Confirm",
    }
    result = await translator.translate_batch(entries, "Chinese (Simplified)")
    assert set(result.keys()) == set(entries.keys()), (
        f"Response keys mismatch. Expected {set(entries.keys())}, got {set(result.keys())}"
    )


@pytest.mark.asyncio
async def test_non_empty_translations(translator):
    """每个 key 翻译结果不能为空字符串。"""
    entries = {"app_name": "RikkaHub", "loading": "Loading..."}
    result = await translator.translate_batch(entries, "Chinese (Simplified)")
    for key, val in result.items():
        assert val and val.strip(), f"Translation for {key!r} is empty"
