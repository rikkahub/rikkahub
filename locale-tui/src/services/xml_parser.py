"""Android strings.xml parser service."""

from pathlib import Path
from lxml import etree


class StringsXmlParser:
    """Android strings.xml parser."""

    @staticmethod
    def parse(file_path: Path) -> dict[str, str]:
        """Parse strings.xml file, returns {name: value} dict."""
        if not file_path.exists():
            return {}

        try:
            with open(file_path, 'r', encoding='utf-8') as f:
                tree = etree.parse(f)
            root = tree.getroot()
            result = {}

            for string_elem in root.findall("string"):
                name = string_elem.get("name")
                if name:
                    value = StringsXmlParser._get_text_content(string_elem)
                    result[name] = value

            return result
        except Exception:
            return {}

    @staticmethod
    def _get_text_content(elem) -> str:
        """Extract element text content."""
        if elem.text:
            return elem.text
        return ""

    @staticmethod
    def write(file_path: Path, entries: dict[str, str]) -> None:
        """Write strings.xml file."""
        root = etree.Element("resources")

        for name in sorted(entries.keys()):
            value = entries[name]
            string_elem = etree.SubElement(root, "string")
            string_elem.set("name", name)
            string_elem.text = value

        # Ensure directory exists
        file_path.parent.mkdir(parents=True, exist_ok=True)

        etree.indent(root, space="  ")
        tree = etree.ElementTree(root)
        tree.write(
            str(file_path), encoding="utf-8", xml_declaration=True, pretty_print=True
        )

    @staticmethod
    def update_entry(file_path: Path, key: str, value: str) -> None:
        """Update single entry."""
        StringsXmlParser.update_entries(file_path, {key: value})

    @staticmethod
    def update_entries(file_path: Path, entries: dict[str, str]) -> None:
        """Update entries in place, keeping existing order and appending new keys."""
        if not file_path.exists():
            StringsXmlParser.write(file_path, entries)
            return

        with open(file_path, 'r', encoding='utf-8') as f:
            tree = etree.parse(f)
        root = tree.getroot()

        pending = dict(entries)
        for string_elem in root.findall("string"):
            key = string_elem.get("name")
            if key in pending:
                string_elem.text = pending.pop(key)

        for key, value in pending.items():
            string_elem = etree.SubElement(root, "string")
            string_elem.set("name", key)
            string_elem.text = value

        # Clean up whitespace and format
        etree.indent(root, space="  ")

        tree.write(
            str(file_path), encoding="utf-8", xml_declaration=True, pretty_print=True
        )

        # Ensure file ends with exactly one newline
        content = file_path.read_text(encoding='utf-8')
        if not content.endswith('\n'):
            file_path.write_text(content + '\n', encoding='utf-8')

    @staticmethod
    def delete_entry(file_path: Path, key: str) -> bool:
        """Delete single entry."""
        if not file_path.exists():
            return False

        with open(file_path, 'r', encoding='utf-8') as f:
            tree = etree.parse(f)
        root = tree.getroot()

        for string_elem in root.findall("string"):
            if string_elem.get("name") == key:
                root.remove(string_elem)
                etree.indent(root, space="  ")
                tree.write(
                    str(file_path),
                    encoding="utf-8",
                    xml_declaration=True,
                    pretty_print=True,
                )
                return True

        return False
