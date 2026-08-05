// Regenerates the golden token fixtures the Kotlin highlighter is checked against.
//
// For every `src/test/resources/hljs/<language>/<name>.txt` this writes a sibling `<name>.tokens`
// holding the flat token stream highlight.js produces for that source. The flattening rules match
// `TokenEmitter` on the Kotlin side: the innermost scope wins, `language:` container scopes are
// transparent, and adjacent tokens sharing a scope are merged.
//
// One token per line, `scope<TAB>text`, with an empty scope meaning unhighlighted text and
// `\\`, `\n`, `\r`, `\t` escaped so a token never spans lines.
//
// Usage: cd highlight/tools && npm install && npm run generate

import hljs from 'highlight.js/lib/core'
import { readdir, readFile, writeFile } from 'node:fs/promises'
import { createRequire } from 'node:module'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { LANGUAGES } from './languages.mjs'

const FIXTURE_ROOT = join(dirname(fileURLToPath(import.meta.url)), '../src/test/resources/hljs')

// Register exactly the grammars the Kotlin highlighter ships, so a `subLanguage` naming something
// we do not support degrades to plain text here as well.
const require = createRequire(import.meta.url)
for (const { name, module, aliases } of LANGUAGES) {
  hljs.registerLanguage(name, require(`highlight.js/lib/languages/${module}`))
  if (aliases.length > 0) hljs.registerAliases(aliases, { languageName: name })
}

/** Walks the highlight.js token tree into a flat `{scope, text}` list. */
function flatten(root) {
  const tokens = []
  const scopes = []

  const push = (text) => {
    if (text === '') return
    const scope = scopes.length === 0 ? null : scopes[scopes.length - 1]
    const previous = tokens[tokens.length - 1]
    if (previous && previous.scope === scope) {
      previous.text += text
    } else {
      tokens.push({ scope, text })
    }
  }

  const walk = (node) => {
    if (typeof node === 'string') {
      push(node)
      return
    }
    // `language:xxx` marks a sub-language container and carries no colour of its own.
    const scoped = node.scope && !node.scope.startsWith('language:')
    if (scoped) scopes.push(node.scope)
    ;(node.children ?? []).forEach(walk)
    if (scoped) scopes.pop()
  }

  walk(root)
  return tokens
}

const escape = (text) =>
  text
    .replaceAll('\\', '\\\\')
    .replaceAll('\n', '\\n')
    .replaceAll('\r', '\\r')
    .replaceAll('\t', '\\t')

async function main() {
  const languages = await readdir(FIXTURE_ROOT, { withFileTypes: true })
  let written = 0

  for (const entry of languages) {
    if (!entry.isDirectory()) continue
    const language = entry.name
    if (!hljs.getLanguage(language)) {
      throw new Error(`highlight.js does not know the language "${language}"`)
    }

    const directory = join(FIXTURE_ROOT, language)
    const sources = (await readdir(directory)).filter((file) => file.endsWith('.txt')).sort()

    for (const source of sources) {
      const code = await readFile(join(directory, source), 'utf8')
      const result = hljs.highlight(code, { language })
      const tokens = flatten(result._emitter.root)

      const rebuilt = tokens.map((token) => token.text).join('')
      if (rebuilt !== code) {
        throw new Error(`token stream for ${language}/${source} does not reproduce the source`)
      }

      const target = join(directory, source.replace(/\.txt$/, '.tokens'))
      const encoded = tokens.map((token) => `${token.scope ?? ''}\t${escape(token.text)}`)
      await writeFile(target, `${encoded.join('\n')}\n`, 'utf8')
      written++
    }
  }

  console.log(`wrote ${written} fixture(s) with highlight.js ${hljs.versionString}`)
}

await main()
