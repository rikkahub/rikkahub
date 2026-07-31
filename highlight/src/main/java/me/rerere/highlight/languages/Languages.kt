package me.rerere.highlight.languages

import me.rerere.highlight.core.Language
import me.rerere.highlight.languages.bash.bash
import me.rerere.highlight.languages.c.c
import me.rerere.highlight.languages.cmake.cmake
import me.rerere.highlight.languages.cpp.cpp
import me.rerere.highlight.languages.csharp.csharp
import me.rerere.highlight.languages.css.css
import me.rerere.highlight.languages.dart.dart
import me.rerere.highlight.languages.diff.diff
import me.rerere.highlight.languages.dockerfile.dockerfile
import me.rerere.highlight.languages.go.go
import me.rerere.highlight.languages.glsl.glsl
import me.rerere.highlight.languages.ini.ini
import me.rerere.highlight.languages.java.java
import me.rerere.highlight.languages.javascript.javascript
import me.rerere.highlight.languages.json.json
import me.rerere.highlight.languages.kotlin.kotlin
import me.rerere.highlight.languages.latex.latex
import me.rerere.highlight.languages.lua.lua
import me.rerere.highlight.languages.markdown.markdown
import me.rerere.highlight.languages.php.php
import me.rerere.highlight.languages.powershell.powershell
import me.rerere.highlight.languages.properties.properties
import me.rerere.highlight.languages.python.python
import me.rerere.highlight.languages.rust.rust
import me.rerere.highlight.languages.ruby.ruby
import me.rerere.highlight.languages.sql.sql
import me.rerere.highlight.languages.swift.swift
import me.rerere.highlight.languages.typescript.typescript
import me.rerere.highlight.languages.xml.xml
import me.rerere.highlight.languages.yaml.yaml

/**
 * Every grammar bundled with the highlighter.
 *
 * Each entry builds a fresh mode tree: compilation mutates modes in place, mirroring `highlight.js`.
 */
internal fun builtinLanguages(): List<Language> = listOf(
    json(),
    ini(),
    cmake(),
    go(),
    glsl(),
    yaml(),
    bash(),
    dockerfile(),
    javascript(),
    typescript(),
    xml(),
    css(),
    dart(),
    java(),
    kotlin(),
    latex(),
    lua(),
    powershell(),
    properties(),
    python(),
    c(),
    cpp(),
    csharp(),
    sql(),
    diff(),
    markdown(),
    rust(),
    ruby(),
    php(),
    swift(),
)
