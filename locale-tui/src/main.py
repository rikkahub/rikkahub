#!/usr/bin/env python3
"""Android Locale Manager TUI Application."""

import sys
import asyncio
from pathlib import Path

# Add src to path for imports
sys.path.insert(0, str(Path(__file__).parent))

import click
from config import Config
from app import LocaleTuiApp
from services.xml_parser import StringsXmlParser
from services.translator import AITranslator
from models.entry import TranslationEntry


def load_config() -> Config:
    """Load configuration from file."""
    config_path = Path(__file__).parent.parent / "config.yml"

    if not config_path.exists():
        click.echo(f"错误：未找到配置文件 {config_path}", err=True)
        click.echo("请基于模板创建 config.yml 文件。", err=True)
        sys.exit(1)

    try:
        config = Config.load(config_path)
    except Exception as e:
        click.echo(f"错误：加载配置失败 - {e}", err=True)
        sys.exit(1)

    # Validate configuration
    if not config.openai_api_key:
        click.echo("警告：未设置 OPENAI_API_KEY。AI 翻译功能将无法使用。", err=True)

    return config


@click.group(invoke_without_command=True)
@click.pass_context
def cli(ctx):
    """Android Locale Manager - 管理和翻译 Android 字符串资源

    不带参数启动 TUI 界面，使用子命令进行命令行操作。
    """
    if ctx.invoked_subcommand is None:
        # No command provided, launch TUI
        config = load_config()
        app = LocaleTuiApp(config)
        app.run()


@cli.command("test-connection")
def test_connection():
    """测试 AI 服务连接

    \b
    示例：
        locale-tui test-connection
    """
    config = load_config()

    if not config.openai_api_key:
        click.echo("错误：未设置 OPENAI_API_KEY，无法测试连接。", err=True)
        sys.exit(1)

    click.echo("AI 服务配置：")
    click.echo(f"  Base URL: {config.openai_base_url}")
    click.echo(f"  Model: {config.translation_model}")
    click.echo("正在测试连接...")

    async def test_async():
        translator = AITranslator(config)
        return await translator.test_connection()

    try:
        content = asyncio.run(test_async())
        click.echo("✓ 连接成功")
        if content:
            click.echo(f"响应: {content}")
        else:
            click.echo("响应为空，但 API 已返回有效结果。")
    except Exception as e:
        click.echo(f"✗ 连接失败: {e}", err=True)
        sys.exit(1)


@cli.command()
@click.argument("key")
@click.argument("value")
@click.option(
    "--module",
    "-m",
    default=None,
    help="模块名称（默认使用配置文件中的第一个模块）",
)
@click.option("--skip-translate", is_flag=True, help="跳过自动翻译，仅添加源语言条目")
def add(key: str, value: str, module: str, skip_translate: bool):
    """添加新的语言条目并自动翻译

    \b
    示例：
        locale-tui add hello_world "Hello, World!"
        locale-tui add greeting "Welcome" -m app
        locale-tui add test_key "Test" --skip-translate
    """
    config = load_config()

    # Select module
    if module:
        selected_module = next((m for m in config.modules if m.name == module), None)
        if not selected_module:
            click.echo(f"错误：未找到模块 '{module}'", err=True)
            click.echo(f"可用模块：{', '.join(m.name for m in config.modules)}", err=True)
            sys.exit(1)
    else:
        if not config.modules:
            click.echo("错误：配置文件中未定义模块", err=True)
            sys.exit(1)
        selected_module = config.modules[0]

    click.echo(f"使用模块: {selected_module.name}")

    # Get source language
    source_lang = config.get_source_language()
    if not source_lang:
        click.echo("错误：未配置源语言", err=True)
        sys.exit(1)

    # Resolve res directory
    res_dir = config.project_root / selected_module.res_path
    if not res_dir.exists():
        click.echo(f"错误：资源目录不存在 {res_dir}", err=True)
        sys.exit(1)

    # Add entry to source language file
    source_file = res_dir / "values" / "strings.xml"
    click.echo(f"添加条目到 {source_file.relative_to(config.project_root)}...")

    try:
        StringsXmlParser.update_entry(source_file, key, value)
        click.echo(f"✓ 已添加条目: {key} = {value}")
    except Exception as e:
        click.echo(f"错误：添加条目失败 - {e}", err=True)
        sys.exit(1)

    # Translate to other languages
    if not skip_translate:
        target_languages = [lang.code for lang in config.languages if not lang.is_source]

        if not target_languages:
            click.echo("未配置目标语言，跳过翻译。")
            return

        click.echo(f"开始翻译到 {len(target_languages)} 种语言...")

        # Create entry for translation
        entry = TranslationEntry(key=key, translations={"values": value})

        async def translate_async():
            translator = AITranslator(config)

            async def translate_one(lang_code: str):
                lang_name = config.get_language_name(lang_code)

                try:
                    translations = await translator.translate_batch(
                        {key: value}, lang_name
                    )

                    if key in translations:
                        return lang_code, lang_name, translations[key], None
                    return lang_code, lang_name, None, "翻译失败（未返回结果）"
                except Exception as e:
                    return lang_code, lang_name, None, str(e)

            tasks = [translate_one(lang_code) for lang_code in target_languages]
            results = await asyncio.gather(*tasks)

            for lang_code, lang_name, translated_value, error in results:
                click.echo(f"翻译到 {lang_name}...", nl=False)

                if error:
                    click.echo(f" ✗ 错误: {error}", err=True)
                    continue

                entry.set_translation(lang_code, translated_value)

                # Save to file
                target_file = res_dir / lang_code / "strings.xml"
                StringsXmlParser.update_entry(target_file, key, translated_value)

                click.echo(f" ✓ {translated_value}")

        asyncio.run(translate_async())
        click.echo("完成！")


@cli.command()
@click.argument("key")
@click.argument("value")
@click.option(
    "--lang",
    "-l",
    default=None,
    help="语言代码（例如：values, values-zh, values-ja），默认为源语言",
)
@click.option(
    "--module",
    "-m",
    default=None,
    help="模块名称（默认使用配置文件中的第一个模块）",
)
def set(key: str, value: str, lang: str, module: str):
    """手动设置指定语言的条目值

    \b
    示例：
        locale-tui set hello_world "你好，世界！" -l values-zh
        locale-tui set greeting "Welcome" -l values
        locale-tui set test_key "テスト" -l values-ja -m app
    """
    config = load_config()

    # Select module
    if module:
        selected_module = next((m for m in config.modules if m.name == module), None)
        if not selected_module:
            click.echo(f"错误：未找到模块 '{module}'", err=True)
            click.echo(f"可用模块：{', '.join(m.name for m in config.modules)}", err=True)
            sys.exit(1)
    else:
        if not config.modules:
            click.echo("错误：配置文件中未定义模块", err=True)
            sys.exit(1)
        selected_module = config.modules[0]

    # Resolve language directory
    if lang is None:
        lang = "values"  # Default to source language

    # Resolve res directory
    res_dir = config.project_root / selected_module.res_path
    if not res_dir.exists():
        click.echo(f"错误：资源目录不存在 {res_dir}", err=True)
        sys.exit(1)

    # Target file
    target_file = res_dir / lang / "strings.xml"
    lang_name = config.get_language_name(lang) if lang != "values" else "源语言"

    click.echo(f"设置 {lang_name} 的条目: {key} = {value}")
    click.echo(f"目标文件: {target_file.relative_to(config.project_root)}")

    try:
        StringsXmlParser.update_entry(target_file, key, value)
        click.echo(f"✓ 设置成功")
    except Exception as e:
        click.echo(f"错误：设置失败 - {e}", err=True)
        sys.exit(1)


@cli.command()
@click.option(
    "--module",
    "-m",
    default=None,
    help="模块名称（默认使用配置文件中的第一个模块）",
)
def list_keys(module: str):
    """列出所有语言条目的键

    \b
    示例：
        locale-tui list-keys
        locale-tui list-keys -m app
    """
    config = load_config()

    # Select module
    if module:
        selected_module = next((m for m in config.modules if m.name == module), None)
        if not selected_module:
            click.echo(f"错误：未找到模块 '{module}'", err=True)
            sys.exit(1)
    else:
        if not config.modules:
            click.echo("错误：配置文件中未定义模块", err=True)
            sys.exit(1)
        selected_module = config.modules[0]

    # Resolve res directory
    res_dir = config.project_root / selected_module.res_path
    source_file = res_dir / "values" / "strings.xml"

    if not source_file.exists():
        click.echo(f"错误：源文件不存在 {source_file}", err=True)
        sys.exit(1)

    # Parse and display
    entries = StringsXmlParser.parse(source_file)

    click.echo(f"模块 '{selected_module.name}' 共有 {len(entries)} 个条目：")
    click.echo()

    for key in sorted(entries.keys()):
        value = entries[key]
        # Truncate long values
        if len(value) > 60:
            value = value[:57] + "..."
        click.echo(f"  {key:40} {value}")


@cli.command("translate-missing")
@click.option(
    "--lang",
    "-l",
    "langs",
    multiple=True,
    help="目标语言代码，可重复（例如：-l values-ar -l values-ja），默认所有非源语言",
)
@click.option(
    "--module",
    "-m",
    "module_names",
    multiple=True,
    help="模块名称，可重复，默认所有模块",
)
@click.option("--concurrency", "-c", default=8, show_default=True, help="并发请求数")
@click.option("--retries", default=3, show_default=True, help="单批次最大尝试次数")
@click.option("--dry-run", is_flag=True, help="只统计缺失条目，不调用翻译")
def translate_missing(
    langs: tuple[str, ...],
    module_names: tuple[str, ...],
    concurrency: int,
    retries: int,
    dry_run: bool,
):
    """批量翻译缺失的条目（适合新增语言后补全）

    \b
    译文会校验占位符与换行是否与原文一致，并自动转义引号；
    校验失败的条目会重试，仍失败则不写入并在最后列出。

    \b
    示例：
        locale-tui translate-missing -l values-ar
        locale-tui translate-missing -m app --dry-run
    """
    config = load_config()

    if not dry_run and not config.openai_api_key:
        click.echo("错误：未设置 OPENAI_API_KEY，无法翻译。", err=True)
        sys.exit(1)

    known_langs = [lang.code for lang in config.languages if not lang.is_source]
    for code in langs:
        if code not in known_langs:
            click.echo(f"错误：未在配置中找到目标语言 '{code}'", err=True)
            click.echo(f"可用语言：{', '.join(known_langs)}", err=True)
            sys.exit(1)
    target_langs = list(langs) or known_langs

    modules = config.modules
    if module_names:
        modules = [m for m in config.modules if m.name in module_names]
        unknown = {*module_names} - {m.name for m in modules}
        if unknown:
            click.echo(f"错误：未找到模块 {', '.join(sorted(unknown))}", err=True)
            click.echo(f"可用模块：{', '.join(m.name for m in config.modules)}", err=True)
            sys.exit(1)

    all_failures: list[tuple[str, str, str, str]] = []

    async def run_all():
        translator = None if dry_run else AITranslator(config)
        for module in modules:
            res_dir = config.project_root / module.res_path
            source_file = res_dir / "values" / "strings.xml"
            if not source_file.exists():
                continue
            source = StringsXmlParser.parse(source_file)

            for lang_code in target_langs:
                lang_name = config.get_language_name(lang_code)
                target_file = res_dir / lang_code / "strings.xml"
                existing = StringsXmlParser.parse(target_file)
                missing = {k: v for k, v in source.items() if k not in existing}
                if not missing:
                    continue

                label = f"[{module.name}/{lang_code}]"
                click.echo(f"{label} 缺失 {len(missing)} 条")
                if dry_run:
                    continue

                def on_progress(done: int, total: int, label=label):
                    click.echo(f"\r{label} {done}/{total}", nl=False)

                translations, failures = await translator.translate_entries(
                    missing,
                    lang_name,
                    concurrency=concurrency,
                    retries=retries,
                    progress_callback=on_progress,
                )
                click.echo()

                if translations:
                    StringsXmlParser.update_entries(target_file, translations)
                click.echo(
                    f"{label} ✓ 写入 {len(translations)}/{len(missing)} 条 -> "
                    f"{target_file.relative_to(config.project_root)}"
                )
                for key, error in failures.items():
                    all_failures.append((module.name, lang_code, key, error))

    asyncio.run(run_all())

    if all_failures:
        click.echo(f"\n{len(all_failures)} 条翻译失败（未写入，可重新运行补全）：", err=True)
        for module_name, lang_code, key, error in all_failures:
            click.echo(f"  {module_name}/{lang_code} {key}: {error}", err=True)
        sys.exit(1)

    click.echo("完成！")


def main():
    """Main entry point."""
    cli()


if __name__ == "__main__":
    main()
