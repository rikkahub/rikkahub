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

            for lang_code in target_languages:
                lang_name = config.get_language_name(lang_code)
                click.echo(f"翻译到 {lang_name}...", nl=False)

                try:
                    translations = await translator.translate_batch(
                        {key: value}, lang_name
                    )

                    if key in translations:
                        translated_value = translations[key]
                        entry.set_translation(lang_code, translated_value)

                        # Save to file
                        target_file = res_dir / lang_code / "strings.xml"
                        StringsXmlParser.update_entry(target_file, key, translated_value)

                        click.echo(f" ✓ {translated_value}")
                    else:
                        click.echo(" ✗ 翻译失败（未返回结果）", err=True)

                except Exception as e:
                    click.echo(f" ✗ 错误: {e}", err=True)

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


def main():
    """Main entry point."""
    cli()


if __name__ == "__main__":
    main()
