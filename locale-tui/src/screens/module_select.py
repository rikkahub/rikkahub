"""Module selection screen."""

from __future__ import annotations

from typing import TYPE_CHECKING

from textual.app import ComposeResult
from textual.screen import Screen
from textual.widgets import Header, Footer, Static, ListView, ListItem, Label
from textual.containers import Container, Vertical

if TYPE_CHECKING:
    from config import Config


class ModuleSelectScreen(Screen):
    """Module selection screen."""

    BINDINGS = [
        ("q", "quit", "Quit"),
    ]

    def __init__(self, config: "Config"):
        super().__init__()
        self.config = config

    def compose(self) -> ComposeResult:
        yield Header()
        yield Container(
            Vertical(
                Static(
                    "Select a module to manage translations:",
                    id="title",
                ),
                ListView(
                    *[
                        ListItem(
                            Label(f"[bold]{m.name}[/bold] - {m.res_path}"),
                            id=f"module-{m.name}",
                        )
                        for m in self.config.modules
                    ],
                    id="module-list",
                ),
                id="content",
            ),
            id="main-container",
        )
        yield Footer()

    def on_list_view_selected(self, event: ListView.Selected) -> None:
        """Handle module selection."""
        from screens.translation_table import TranslationTableScreen

        # Extract module name from ListItem ID
        item_id = event.item.id
        if item_id:
            module_name = item_id.replace("module-", "")

            # Find corresponding module
            module = next(
                (m for m in self.config.modules if m.name == module_name), None
            )

            if module:
                self.app.push_screen(TranslationTableScreen(self.config, module))

    def action_quit(self) -> None:
        self.app.exit()
