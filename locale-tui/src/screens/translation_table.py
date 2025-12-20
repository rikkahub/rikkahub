"""Translation table screen."""

from __future__ import annotations

from typing import TYPE_CHECKING

from textual.app import ComposeResult
from textual.screen import Screen
from textual.widgets import Header, Footer, DataTable, Input, Static, ProgressBar
from textual.containers import Container, Horizontal, Vertical
from textual.binding import Binding
from textual import work

from models.entry import TranslationEntry
from services.xml_parser import StringsXmlParser
from services.translator import AITranslator
from services.dead_entry_finder import DeadEntryFinder

if TYPE_CHECKING:
    from config import Config, ModuleConfig


class TranslationTableScreen(Screen):
    """Translation table screen."""

    BINDINGS = [
        Binding("escape", "go_back", "Back"),
        Binding("t", "translate_missing", "Translate"),
        Binding("d", "toggle_dead_filter", "Dead Filter"),
        Binding("m", "toggle_missing_filter", "Missing Filter"),
        Binding("slash", "focus_search", "Search"),
        Binding("delete", "delete_entry", "Delete"),
        Binding("s", "save_all", "Save"),
        Binding("enter", "edit_entry", "Edit"),
        Binding("r", "refresh", "Refresh"),
    ]

    def __init__(self, config: "Config", module: "ModuleConfig"):
        super().__init__()
        self.config = config
        self.module = module
        self.entries: list[TranslationEntry] = []
        self.filtered_entries: list[TranslationEntry] = []
        self.show_dead_only = False
        self.show_missing_only = False
        self.search_query = ""
        self.has_unsaved_changes = False

    def compose(self) -> ComposeResult:
        yield Header()
        yield Container(
            Vertical(
                # Status bar
                Horizontal(
                    Static(f"Module: [bold]{self.module.name}[/bold]", id="module-name"),
                    Static("", id="status"),
                    Static("", id="filter-status"),
                    id="status-bar",
                ),
                # Search box
                Input(placeholder="Search entries... (press / to focus)", id="search"),
                # Progress bar (hidden)
                ProgressBar(total=100, show_eta=False, id="progress"),
                # Translation table
                DataTable(id="table", cursor_type="row", zebra_stripes=True),
                id="content",
            ),
            id="main-container",
        )
        yield Footer()

    def on_mount(self) -> None:
        """Initialize data when screen loads."""
        # Hide progress bar
        self.query_one("#progress", ProgressBar).display = False

        # Setup table columns
        table = self.query_one("#table", DataTable)
        table.add_column("Key", key="key", width=30)

        for lang in self.config.languages:
            col_name = lang.name if len(lang.name) <= 15 else lang.code
            table.add_column(col_name, key=lang.code, width=25)

        # Load data
        self.load_entries()

        # Focus table by default
        table.focus()

    def load_entries(self) -> None:
        """Load all translation entries."""
        self.entries = []
        all_keys: set[str] = set()
        translations_by_lang: dict[str, dict[str, str]] = {}

        # Collect translations from all languages
        for lang in self.config.languages:
            path = (
                self.config.project_root
                / self.module.res_path
                / lang.code
                / "strings.xml"
            )
            translations = StringsXmlParser.parse(path)
            translations_by_lang[lang.code] = translations
            all_keys.update(translations.keys())

        # Create entries
        for key in sorted(all_keys):
            entry = TranslationEntry(key=key)
            for lang_code, translations in translations_by_lang.items():
                entry.translations[lang_code] = translations.get(key)
            self.entries.append(entry)

        # Mark dead entries
        if self.module.source_patterns:
            finder = DeadEntryFinder(self.config.project_root)
            dead_count = finder.mark_dead_entries(
                self.entries, self.module.source_patterns
            )
            self.notify(f"Found {dead_count} dead entries")

        self.apply_filters()
        self.update_status()

    def apply_filters(self) -> None:
        """Apply search and filter conditions."""
        self.filtered_entries = self.entries.copy()

        # Apply dead filter
        if self.show_dead_only:
            self.filtered_entries = [e for e in self.filtered_entries if e.is_dead]

        # Apply missing filter
        if self.show_missing_only:
            lang_codes = self.config.get_language_codes()
            self.filtered_entries = [
                e for e in self.filtered_entries
                if e.has_missing_translations(lang_codes)
            ]

        # Apply search
        if self.search_query:
            query = self.search_query.lower()
            self.filtered_entries = [
                e
                for e in self.filtered_entries
                if query in e.key.lower()
                or any(query in (v or "").lower() for v in e.translations.values())
            ]

        self.refresh_table()

    def refresh_table(self) -> None:
        """Refresh table display."""
        table = self.query_one("#table", DataTable)
        table.clear()

        for entry in self.filtered_entries:
            row_data = [entry.key]
            for lang in self.config.languages:
                value = entry.translations.get(lang.code, "")
                # Highlight missing translations
                if not value and lang.code != "values":
                    row_data.append("[red]MISSING[/red]")
                elif entry.is_dead:
                    row_data.append(f"[dim]{value or ''}[/dim]")
                else:
                    # Truncate long values for display
                    display_value = value or ""
                    if len(display_value) > 30:
                        display_value = display_value[:27] + "..."
                    row_data.append(display_value)

            table.add_row(*row_data, key=entry.key)

    def update_status(self) -> None:
        """Update status bar."""
        total = len(self.entries)
        missing = sum(
            1
            for e in self.entries
            if e.has_missing_translations(self.config.get_language_codes())
        )
        dead = sum(1 for e in self.entries if e.is_dead)

        status = f"Total: {total} | Missing: {missing} | Dead: {dead}"
        if self.has_unsaved_changes:
            status += " | [yellow]Unsaved[/yellow]"

        self.query_one("#status", Static).update(status)

        # Update filter status
        filter_text = []
        if self.show_dead_only:
            filter_text.append("[cyan]Dead Only[/cyan]")
        if self.show_missing_only:
            filter_text.append("[cyan]Missing Only[/cyan]")
        if self.search_query:
            filter_text.append(f"[cyan]Search: {self.search_query}[/cyan]")

        self.query_one("#filter-status", Static).update(" | ".join(filter_text))

    def on_input_changed(self, event: Input.Changed) -> None:
        """Search box content changed."""
        if event.input.id == "search":
            self.search_query = event.value
            self.apply_filters()
            self.update_status()

    def action_go_back(self) -> None:
        """Go back to previous screen."""
        if self.has_unsaved_changes:
            self.notify(
                "You have unsaved changes! Press 's' to save or 'escape' again to discard."
            )
            self.has_unsaved_changes = False  # Allow second escape to exit
        else:
            self.app.pop_screen()

    def action_focus_search(self) -> None:
        """Focus search box."""
        self.query_one("#search", Input).focus()

    def action_toggle_dead_filter(self) -> None:
        """Toggle dead entry filter."""
        self.show_dead_only = not self.show_dead_only
        self.apply_filters()
        self.update_status()
        self.notify(f"Dead filter: {'ON' if self.show_dead_only else 'OFF'}")

    def action_toggle_missing_filter(self) -> None:
        """Toggle missing translation filter."""
        self.show_missing_only = not self.show_missing_only
        self.apply_filters()
        self.update_status()
        self.notify(f"Missing filter: {'ON' if self.show_missing_only else 'OFF'}")

    def action_edit_entry(self) -> None:
        """Edit current selected entry."""
        from widgets.edit_modal import EditModal

        table = self.query_one("#table", DataTable)
        if table.cursor_row is None:
            return

        row_key, _ = table.coordinate_to_cell_key(table.cursor_coordinate)
        entry = next((e for e in self.entries if e.key == row_key.value), None)

        if entry:
            self.app.push_screen(
                EditModal(entry, self.config.languages), callback=self.on_edit_complete
            )

    def on_edit_complete(self, result: dict | None) -> None:
        """Edit complete callback."""
        if result:
            entry_key = result["key"]
            entry = next((e for e in self.entries if e.key == entry_key), None)
            if entry:
                for lang_code, value in result["translations"].items():
                    entry.set_translation(lang_code, value)
                self.has_unsaved_changes = True
                self.refresh_table()
                self.update_status()
                self.notify(f"Updated: {entry_key}")

    def action_delete_entry(self) -> None:
        """Delete current selected entry."""
        table = self.query_one("#table", DataTable)
        if table.cursor_row is None:
            return

        row_key, _ = table.coordinate_to_cell_key(table.cursor_coordinate)
        entry_key = row_key.value

        # Delete from all language files
        for lang in self.config.languages:
            path = (
                self.config.project_root
                / self.module.res_path
                / lang.code
                / "strings.xml"
            )
            StringsXmlParser.delete_entry(path, entry_key)

        # Delete from memory
        self.entries = [e for e in self.entries if e.key != entry_key]
        self.apply_filters()
        self.update_status()
        self.notify(f"Deleted: {entry_key}")

    @work(exclusive=True)
    async def action_translate_missing(self) -> None:
        """Translate all missing entries."""
        translator = AITranslator(self.config)
        progress = self.query_one("#progress", ProgressBar)
        progress.display = True

        try:
            # Collect entries needing translation
            entries_to_translate = [
                e
                for e in self.entries
                if e.has_missing_translations(self.config.get_language_codes())
            ]

            if not entries_to_translate:
                self.notify("No missing translations found!")
                progress.display = False
                return

            self.notify(f"Translating {len(entries_to_translate)} entries...")

            def update_progress(
                lang_code: str, current: int, total: int, message: str
            ) -> None:
                progress.update(progress=(current / total) * 100)
                self.query_one("#status", Static).update(message)

            count = await translator.translate_all_missing(
                entries_to_translate,
                self.config.get_language_codes(),
                progress_callback=update_progress,
            )

            self.has_unsaved_changes = True
            self.refresh_table()
            self.update_status()
            self.notify(f"Translated {count} entries!")

        except Exception as e:
            self.notify(f"Translation failed: {e}", severity="error")
        finally:
            progress.display = False

    def action_save_all(self) -> None:
        """Save all changes."""
        for lang in self.config.languages:
            path = (
                self.config.project_root
                / self.module.res_path
                / lang.code
                / "strings.xml"
            )

            # Collect translations for this language
            translations = {}
            for entry in self.entries:
                value = entry.get_translation(lang.code)
                if value:
                    translations[entry.key] = value

            if translations:
                StringsXmlParser.write(path, translations)

        self.has_unsaved_changes = False
        self.update_status()
        self.notify("All changes saved!")

    def action_refresh(self) -> None:
        """Refresh data."""
        self.load_entries()
        self.notify("Data refreshed!")
