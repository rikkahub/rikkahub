// The grammars bundled by the Kotlin highlighter, with the aliases it answers to.
//
// The fixture generator registers exactly these with highlight.js so that sub-language lookups
// resolve the same way on both sides: a `subLanguage` naming a grammar we do not ship must fall
// back to plain text in the golden fixtures too.

export const LANGUAGES = [
  { name: 'json', module: 'json', aliases: ['json5'] },
  { name: 'ini', module: 'ini', aliases: ['ini'] },
  { name: 'cmake', module: 'cmake', aliases: [] },
  { name: 'go', module: 'go', aliases: [] },
  { name: 'yaml', module: 'yaml', aliases: [] },
  { name: 'bash', module: 'bash', aliases: ['shell'] },
  { name: 'dockerfile', module: 'dockerfile', aliases: [] },
  { name: 'javascript', module: 'javascript', aliases: ['js', 'jsx', 'mjs', 'cjs'] },
  { name: 'typescript', module: 'typescript', aliases: ['ts', 'tsx', 'mts', 'cts'] },
  {
    name: 'xml',
    module: 'xml',
    aliases: ['html', 'xhtml', 'rss', 'atom', 'xjb', 'xsd', 'xsl', 'plist', 'wsf', 'svg'],
  },
  { name: 'css', module: 'css', aliases: [] },
]
