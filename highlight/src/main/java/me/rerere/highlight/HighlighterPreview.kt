package me.rerere.highlight

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp

@Preview
@Composable
private fun HighlightTextPreview(
    @PreviewParameter(HighlightPreviewProvider::class)
    sample: HighlightPreviewSample,
) {
    Surface(
        color = Color(0xFF282C34),
        contentColor = Color(0xFFABB2BF),
    ) {
        CodeHighlightText(
            code = sample.code,
            language = sample.language,
            modifier = Modifier.padding(16.dp),
        )
    }
}

private data class HighlightPreviewSample(
    val language: String,
    val code: String,
)

private class HighlightPreviewProvider : PreviewParameterProvider<HighlightPreviewSample> {
    /** Titles every preview after the language it renders instead of after its position. */
    override fun getDisplayName(index: Int): String = values.elementAt(index).language

    override val values = sequenceOf(
        HighlightPreviewSample(
            language = "json",
            code = """
                {
                  "name": "Rikka",
                  "age": 18,
                  "active": true
                }
            """.trimIndent(),
        ),
        HighlightPreviewSample(
            language = "bash",
            code = """
                #!/usr/bin/env bash
                name="Rikka"
                echo "Hello, ${'$'}name!"
            """.trimIndent(),
        ),
        HighlightPreviewSample(
            language = "go",
            code = """
                package main

                import "fmt"

                func main() {
                    user := "Rikka"
                    fmt.Println("Hello,", user)
                }
            """.trimIndent(),
        ),
        HighlightPreviewSample(
            language = "toml",
            code = """
                [user]
                name = "Rikka"
                age = 18
                active = true
            """.trimIndent(),
        ),
        HighlightPreviewSample(
            language = "properties",
            code = """
                # Application settings
                app.name = RikkaHub
                app.version=1.4.0
                app.locale : zh-CN
                app.debug false
            """.trimIndent(),
        ),
        HighlightPreviewSample(
            language = "yaml",
            code = """
                user:
                  name: Rikka
                  age: 18
                  tags:
                    - chat
                    - llm
            """.trimIndent(),
        ),
        HighlightPreviewSample(
            language = "dockerfile",
            code = """
                FROM eclipse-temurin:21-jre
                WORKDIR /app
                COPY app.jar .
                CMD ["java", "-jar", "app.jar"]
            """.trimIndent(),
        ),
        HighlightPreviewSample(
            language = "javascript",
            code = """
                const user = "Rikka"

                function greet(name) {
                  console.log(`Hello, ${'$'}{name}!`)
                }

                greet(user)
            """.trimIndent(),
        ),
        HighlightPreviewSample(
            language = "typescript",
            code = """
                interface User {
                  name: string
                  age?: number
                }

                const greet = (user: User): void => {
                  console.log(`Hello, ${'$'}{user.name}!`)
                }
            """.trimIndent(),
        ),
        HighlightPreviewSample(
            language = "html",
            code = """
                <div class="chat">
                  <p>Hello, Rikka!</p>
                  <img src="logo.svg" alt="logo" />
                </div>
            """.trimIndent(),
        ),
        HighlightPreviewSample(
            language = "css",
            code = """
                .chat {
                  display: flex;
                  color: #61afef;
                  margin: 0 auto;
                }
            """.trimIndent(),
        ),
        HighlightPreviewSample(
            language = "glsl",
            code = """
                #version 450

                layout(location = 0) in vec3 position;
                uniform mat4 transform;

                void main() {
                  gl_Position = transform * vec4(position, 1.0);
                }
            """.trimIndent(),
        ),
        HighlightPreviewSample(
            language = "dart",
            code = """
                class User {
                  final String name;
                  final int age;

                  const User(this.name, {this.age = 18});

                  String greet() => "Hello, ${'$'}name!";
                }
            """.trimIndent(),
        ),
        HighlightPreviewSample(
            language = "java",
            code = """
                public final class Greeter {
                    private final String name;

                    public String greet() {
                        return "Hello, " + name + "!";
                    }
                }
            """.trimIndent(),
        ),
        HighlightPreviewSample(
            language = "kotlin",
            code = """
                data class User(val name: String, val age: Int = 18)

                fun greet(user: User) {
                    println("Hello, ${'$'}{user.name}!")
                }
            """.trimIndent(),
        ),
        HighlightPreviewSample(
            language = "lua",
            code = """
                local User = {}
                User.__index = User

                function User:new(name)
                  return setmetatable({ name = name }, self)
                end

                function User:greet()
                  print("Hello, " .. self.name .. "!")
                end
            """.trimIndent(),
        ),
        HighlightPreviewSample(
            language = "powershell",
            code = """
                function Get-Greeting {
                    param(
                        [string]${'$'}Name = "Rikka",
                        [int]${'$'}Age = 18
                    )

                    # Nothing to do without a name.
                    if (${'$'}Name -eq '') { return }

                    Write-Output "Hello, ${'$'}Name!"
                }
            """.trimIndent(),
        ),
        HighlightPreviewSample(
            language = "ruby",
            code = """
                class User
                  attr_reader :name

                  def initialize(name, age: 18)
                    @name = name
                    @age = age
                  end

                  def greet = "Hello, #{name}!"
                end
            """.trimIndent(),
        ),
        HighlightPreviewSample(
            language = "python",
            code = """
                from dataclasses import dataclass


                @dataclass
                class User:
                    name: str
                    age: int = 18

                    def greet(self) -> str:
                        return f"Hello, {self.name}!"
            """.trimIndent(),
        ),
        HighlightPreviewSample(
            language = "c",
            code = """
                #include <stdio.h>

                int main(void)
                {
                    const char *name = "Rikka";
                    printf("Hello, %s!\n", name);
                    return 0;
                }
            """.trimIndent(),
        ),
        HighlightPreviewSample(
            language = "cpp",
            code = """
                #include <iostream>
                #include <string>

                int main() {
                    const std::string name = "Rikka";
                    std::cout << "Hello, " << name << std::endl;
                    return 0;
                }
            """.trimIndent(),
        ),
        HighlightPreviewSample(
            language = "csharp",
            code = """
                namespace RikkaHub;

                public sealed record User(string Name, int Age = 18)
                {
                    public string Greet() => ${'$'}"Hello, {Name}!";
                }

                Console.WriteLine(new User("Rikka").Greet());
            """.trimIndent(),
        ),
        HighlightPreviewSample(
            language = "rust",
            code = """
                #[derive(Debug)]
                struct User {
                    name: String,
                    age: u8,
                }

                fn main() {
                    let user = User { name: "Rikka".to_string(), age: 18 };
                    println!("Hello, {}!", user.name);
                }
            """.trimIndent(),
        ),
        HighlightPreviewSample(
            language = "php",
            code = """
                <?php

                final readonly class User
                {
                    public function __construct(
                        public string ${'$'}name,
                        public int ${'$'}age = 18,
                    ) {}

                    public function greet(): string
                    {
                        return "Hello, {${'$'}this->name}!";
                    }
                }
            """.trimIndent(),
        ),
        HighlightPreviewSample(
            language = "swift",
            code = """
                import Foundation

                struct User {
                    let name: String
                    var age: Int = 18

                    func greet() -> String {
                        "Hello, \(name)!"
                    }
                }

                print(User(name: "Rikka").greet())
            """.trimIndent(),
        ),
        HighlightPreviewSample(
            language = "sql",
            code = """
                SELECT name, age
                  FROM users
                 WHERE active = true
                 ORDER BY age DESC
                 LIMIT 10;
            """.trimIndent(),
        ),
        HighlightPreviewSample(
            language = "diff",
            code = """
                --- a/greeter.kt
                +++ b/greeter.kt
                @@ -1,3 +1,3 @@
                 fun greet(name: String) {
                -    println("Hi, " + name)
                +    println("Hello, " + name)
                 }
            """.trimIndent(),
        ),
        HighlightPreviewSample(
            language = "markdown",
            code = """
                # RikkaHub

                A native **Android** LLM chat client.

                - [Docs](https://example.com)
                - Inline `code`
            """.trimIndent(),
        ),
        HighlightPreviewSample(
            language = "latex",
            code = """
                \documentclass{article}
                \usepackage{amsmath}

                \begin{document}
                \section{Greeting}
                \[
                  E = mc^2
                \]
                \end{document}
            """.trimIndent(),
        ),
        HighlightPreviewSample(
            language = "cmake",
            code = """
                cmake_minimum_required(VERSION 3.22)
                project(rikka LANGUAGES CXX)
                add_executable(rikka main.cpp)
            """.trimIndent(),
        ),
    )
}
