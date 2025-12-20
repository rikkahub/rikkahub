"""Edit modal widget."""

from __future__ import annotations

from typing import TYPE_CHECKING

from textual.app import ComposeResult
from textual.screen import ModalScreen
from textual.widgets import Static, Input, Button, Label
from textual.containers import Container, Horizontal, VerticalScroll

if TYPE_CHECKING:
    from models.entry import TranslationEntry
    from config import LanguageConfig


class EditModal(ModalScreen[dict | None]):
    """Translation entry edit modal."""

    BINDINGS = [
        ("escape", "cancel", "Cancel"),
        ("ctrl+s", "save", "Save"),
    ]

    def __init__(
        self, entry: "TranslationEntry", languages: list["LanguageConfig"]
    ) -> None:
        super().__init__()
        self.entry = entry
        self.languages = languages

    def compose(self) -> ComposeResult:
        with Container(id="edit-modal"):
            yield Static(f"Edit: [bold]{self.entry.key}[/bold]", id="modal-title")

            with VerticalScroll(id="edit-form"):
                for lang in self.languages:
                    yield Label(f"{lang.name}:")
                    yield Input(
                        value=self.entry.get_translation(lang.code) or "",
                        id=f"input-{lang.code}",
                        placeholder=f"Enter {lang.name} translation...",
                    )

            with Horizontal(id="button-row"):
                yield Button("Save", variant="primary", id="save-btn")
                yield Button("Cancel", variant="default", id="cancel-btn")

    def on_button_pressed(self, event: Button.Pressed) -> None:
        if event.button.id == "save-btn":
            self.action_save()
        else:
            self.action_cancel()

    def action_save(self) -> None:
        """Save changes."""
        translations = {}
        for lang in self.languages:
            input_widget = self.query_one(f"#input-{lang.code}", Input)
            translations[lang.code] = input_widget.value

        self.dismiss({"key": self.entry.key, "translations": translations})

    def action_cancel(self) -> None:
        """Cancel edit."""
        self.dismiss(None)
