package me.rerere.highlight.kotlin.languages.rust

import me.rerere.highlight.kotlin.KotlinHighlighter
import me.rerere.highlight.kotlin.assertPreservesSource
import me.rerere.highlight.kotlin.assertToken
import org.junit.Assert.assertTrue
import org.junit.Test

class RustHighlighterTest {
    private val highlighter = KotlinHighlighter()

    @Test
    fun `highlights Rust attributes declarations and types`() {
        val code = """
            #[derive(Debug, Clone)]
            pub struct Repository<T> {
                values: Vec<T>,
            }

            impl<T> Repository<T> {
                pub async fn find(&self, id: u64) -> Option<T> {
                    Some(self.values[id as usize])
                }
            }
        """.trimIndent()

        val tokens = highlighter.highlight(code, "rust")

        assertPreservesSource(code, tokens)
        assertTrue(highlighter.supports("rust"))
        assertTrue(highlighter.supports("rs"))
        assertToken(tokens, "#[derive(Debug, Clone)]", "important")
        assertToken(tokens, "pub", "keyword")
        assertToken(tokens, "struct", "keyword")
        assertToken(tokens, "Repository", "class-name")
        assertToken(tokens, "Vec", "class-name")
        assertToken(tokens, "impl", "keyword")
        assertToken(tokens, "async", "keyword")
        assertToken(tokens, "fn", "keyword")
        assertToken(tokens, "find", "function")
        assertToken(tokens, "u64", "class-name")
        assertToken(tokens, "Option", "class-name")
        assertToken(tokens, "Some", "constant")
    }

    @Test
    fun `highlights Rust lifetimes raw identifiers and bindings`() {
        val code = """
            fn borrow<'a>(value: &'a str) -> &'a str {
                let mut r#type = value;
                'outer: loop {
                    for item in values {
                        break 'outer;
                    }
                }
                r#type
            }
        """.trimIndent()

        val tokens = highlighter.highlight(code, "rs")

        assertPreservesSource(code, tokens)
        assertToken(tokens, "borrow", "function")
        assertToken(tokens, "'a", "important")
        assertToken(tokens, "str", "class-name")
        assertToken(tokens, "let", "keyword")
        assertToken(tokens, "mut", "keyword")
        assertToken(tokens, "r#type", "variable")
        assertToken(tokens, "'outer", "important")
        assertToken(tokens, "loop", "keyword")
        assertToken(tokens, "for", "keyword")
        assertToken(tokens, "item", "variable")
        assertToken(tokens, "in", "keyword")
    }

    @Test
    fun `highlights Rust strings numbers macros and nested comments`() {
        val code = """
            /* outer /* nested */ comment */
            let raw = r###"{"key": "# value"}"###;
            let bytes = b"bytes";
            let newline = '\n';
            let byte = b'\x7f';
            let mask = 0xff_u8;
            let count = 1_000usize;
            let ratio = 3.14e-2f64;
            let values = vec![1, 2, 3];
            println!("{:?}", values);
        """.trimIndent()

        val tokens = highlighter.highlight(code, "rust")

        assertPreservesSource(code, tokens)
        assertToken(tokens, "/* outer /* nested */ comment */", "comment")
        assertToken(tokens, """r###"{"key": "# value"}"###""", "string")
        assertToken(tokens, "b\"bytes\"", "string")
        assertToken(tokens, "'\\n'", "string")
        assertToken(tokens, "b'\\x7f'", "string")
        assertToken(tokens, "0xff_u8", "number")
        assertToken(tokens, "1_000usize", "number")
        assertToken(tokens, "3.14e-2f64", "number")
        assertToken(tokens, "vec!", "function")
        assertToken(tokens, "println!", "function")
    }

    @Test
    fun `preserves incomplete Rust constructs`() {
        val samples = listOf(
            """let value = "unfinished""",
            """let raw = r##"unfinished"#""",
            "/* outer /* nested */",
            "#[derive(Debug",
            "let value: &'a",
        )

        samples.forEach { code ->
            assertPreservesSource(code, highlighter.highlight(code, "rust"))
        }
    }
}
