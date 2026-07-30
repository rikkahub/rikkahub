package me.rerere.highlight.kotlin.languages.cmake

internal object CMakeGrammar {
    val keywords = setOf(
        "add_compile_definitions", "add_compile_options", "add_custom_command",
        "add_custom_target", "add_definitions", "add_dependencies", "add_executable",
        "add_library", "add_link_options", "add_subdirectory", "add_test", "block", "break",
        "build_command", "cmake_host_system_information", "cmake_minimum_required",
        "cmake_parse_arguments", "cmake_policy", "configure_file", "continue",
        "create_test_sourcelist", "ctest_build", "ctest_configure", "ctest_coverage",
        "ctest_empty_binary_directory", "ctest_memcheck", "ctest_read_custom_files",
        "ctest_run_script", "ctest_sleep", "ctest_start", "ctest_submit", "ctest_test",
        "ctest_update", "ctest_upload", "define_property", "elseif", "else", "enable_language",
        "enable_testing", "endblock", "endforeach", "endfunction", "endif", "endmacro",
        "endwhile", "execute_process", "export", "file", "find_file", "find_library",
        "find_package", "find_path", "find_program", "foreach", "function",
        "get_cmake_property", "get_directory_property", "get_filename_component",
        "get_property", "get_source_file_property", "get_target_property", "get_test_property",
        "if", "include", "include_directories", "include_external_msproject", "include_guard",
        "include_regular_expression", "install", "link_directories", "link_libraries", "list",
        "macro", "mark_as_advanced", "math", "message", "option", "project", "qt_wrap_cpp",
        "qt_wrap_ui", "remove_definitions", "return", "separate_arguments", "set",
        "set_directory_properties", "set_property", "set_source_files_properties",
        "set_target_properties", "set_tests_properties", "site_name", "source_group", "string",
        "target_compile_definitions", "target_compile_features", "target_compile_options",
        "target_include_directories", "target_link_directories", "target_link_libraries",
        "target_link_options", "target_sources", "try_compile", "try_run", "unset",
        "variable_watch", "while",
        "and", "command", "defined", "equal", "exists", "greater", "greater_equal", "in_list",
        "is_absolute", "is_directory", "is_newer_than", "is_symlink", "less", "less_equal",
        "matches", "not", "or", "policy", "strequal", "strgreater", "strgreater_equal",
        "strless", "strless_equal", "target", "test", "version_equal", "version_greater",
        "version_greater_equal", "version_less", "version_less_equal",
    )

    val booleans = setOf("on", "off", "true", "false", "yes", "no", "y", "n")

    fun isIdentifierStart(char: Char): Boolean {
        return char == '_' || char.isLetter()
    }

    fun isIdentifierPart(char: Char): Boolean {
        return isIdentifierStart(char) || char.isDigit()
    }
}
