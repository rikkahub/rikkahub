"""Main Textual application."""

from __future__ import annotations

from pathlib import Path
from typing import TYPE_CHECKING

from textual.app import App
from textual.binding import Binding

from screens.module_select import ModuleSelectScreen

if TYPE_CHECKING:
    from config import Config


class LocaleTuiApp(App):
    """Android locale file translation management TUI application."""

    CSS_PATH = Path(__file__).parent / "styles" / "app.tcss"

    TITLE = "Android Locale Manager"

    BINDINGS = [
        Binding("q", "quit", "Quit"),
        Binding("question_mark", "help", "Help"),
    ]

    def __init__(self, config: "Config"):
        super().__init__()
        self.config = config

    def on_mount(self) -> None:
        """Show module selection screen on app start."""
        self.push_screen(ModuleSelectScreen(self.config))

    def action_help(self) -> None:
        """Show help information."""
        self.notify(
            "Key bindings:\n"
            "  Enter - Select/Edit\n"
            "  t - Translate missing\n"
            "  d - Toggle dead filter\n"
            "  / - Search\n"
            "  Delete - Delete entry\n"
            "  s - Save changes\n"
            "  q - Quit/Back"
        )
