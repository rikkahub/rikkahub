# highlight

Syntax highlighting for the app's code blocks. The engine is a Kotlin port of
[highlight.js](https://github.com/highlightjs/highlight.js) **11.11.1** — upstream is the source of
truth for both the architecture and the output.

## Layout

```
me/rerere/highlight/
├── HighlightToken.kt      Plain / Styled(type, content)
├── HighlightStyle.kt      hljs scope → SpanStyle, with tiered fallback (title.function → title)
├── KotlinHighlighter.kt   public entry point + KotlinHighlightText composable
├── core/                  the engine (internal)
└── languages/             one package per grammar + Languages.kt registry
tools/                     Node script that captures golden fixtures from highlight.js
```

`core/` mirrors `lib/core.js`, `lib/modes.js` and `lib/regex.js`:

| file | upstream counterpart |
| --- | --- |
| `Mode.kt` | the Mode data model + `Language` |
| `ModeCompiler.kt` | `compileLanguage` / `compileMode` and its extensions |
| `HighlightEngine.kt` | `_highlight`, the mode-stack loop |
| `MultiRegex.kt` | `MultiRegex` / `ResumableMultiRegex` |
| `Keywords.kt` | keyword compilation and relevance |
| `Regexes.kt` | `lib/regex.js` **+ JS→`java.util.regex` translation** |
| `CommonModes.kt` | `lib/modes.js` (`C_LINE_COMMENT_MODE`, `NUMBER_MODE`, …) |
| `TokenEmitter.kt` | flattens the scope tree into a `HighlightToken` list |

## Working on this module

- **Transcribe upstream verbatim.** Grammars are written with JS-flavoured regex; `translateJsRegex`
  in `Regexes.kt` is the single place that bridges the dialects (`[^]`, `[]`, literal `[`/`&` in a
  character class, bare `{`, the `\p{XID_…}` property names). Fix regex incompatibilities there, not
  in individual grammars.
- Shared modes in `CommonModes.kt` are `.frozen()` because compilation mutates modes in place;
  `builtinLanguages()` builds a fresh tree per engine instance for the same reason.
- The emitter is flat, so **the innermost scope wins** — e.g. JSON's `true` ends up `keyword`, not
  `literal`, because `beginKeywords` opens a nested scope inside the mode's `literal` one.
- Anything the engine can't handle degrades to plain text. Set `highlightDebugMode = true` (tests do)
  to make failures throw instead.
- `typescript` extends the `javascript` mode tree the way upstream does, by patching the modes it
  gets back — `Mode.label` is what it finds them by. The one deviation: upstream mutates the shared
  keyword object in place, which Kotlin cannot, so `javascriptGrammar()` takes the keywords as a
  parameter instead. Fixtures are the source of truth that the two stay equivalent.
- `Mode.variants` are expanded **once per mode instance** and the result is cached
  (`Mode.cachedVariants`), like upstream. That is not an optimisation: a mode whose variants list
  the mode itself — Kotlin's parenthesised type — only terminates because the second expansion
  returns objects the first one already compiled. It also means two `contains` lists holding the
  same variant mode end up sharing its expansion.
- `Mode.subLanguageList` is `subLanguage: ['css', 'xml']` upstream, and it means **auto detection**,
  not "try in order": every candidate is highlighted and the highest relevance wins, with a plain
  text result winning any tie. That makes `<style>` and `<script>` bodies sensitive to relevance
  parity — a scoring bug shows up as the wrong language being picked.

## Adding a language

1. `languages/<lang>/<Lang>.kt` — port `lib/languages/<lang>.js`, return a `Language`.
2. Register it in `languages/Languages.kt` **and** in `tools/languages.mjs` (the generator must ship
   the same set, so an unsupported `subLanguage` falls back to plain text on both sides).
3. Add `src/test/resources/hljs/<lang>/sample.txt`, then
   `cd highlight/tools && npm install && npm run generate`.
4. Add one line to `LanguageFixtureTest`.

The fixture directory is named after the alias used in the test, which may differ from the grammar
name (`ini` ships as `toml`).

## Currently bundled

json · ini (toml) · cmake · go · yaml · bash · dockerfile · javascript · typescript · xml (html) ·
css · java · kotlin · python · c · cpp · sql · diff · markdown

Everything else — rust, swift, csharp, php, ruby — is **not ported yet** and renders as plain text.
JavaScript's `gql` tagged templates and JSX name sub-languages name grammars we do not ship, so
their bodies stay plain, upstream included.

`shell` is an alias of `bash` here, whereas upstream ships it as a separate grammar for terminal
*sessions*. Both sides agree, so fixtures do not notice, but a pasted `$ ` prompt is read as bash.

## Tests

```
./gradlew :highlight:testDebugUnitTest
```

`LanguageFixtureTest` asserts token-for-token equality with highlight.js; `HighlightEngineTest` and
`RegexesTest` pin engine features that fixtures only reach indirectly.
