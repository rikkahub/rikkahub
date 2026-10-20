cmake_minimum_required(VERSION 3.22.1)

project("terminal")

# 库名必须是 termux: com.termux.terminal.JNI 按这个名字加载
add_library(termux SHARED
        termux_pty.cpp)

target_link_libraries(termux
        log)
