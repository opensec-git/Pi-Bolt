# libtermux.so for Termux's terminal-emulator Java classes (com.termux.terminal.JNI), built from the vendored termux.c
# (Apache-2.0, termux-app v0.118.3) with Termux's own flags, plus 16 KB pages so it loads on 16 KB page devices.
cmake_minimum_required(VERSION 3.22.1)
project(termux C)

add_library(termux SHARED termux.c)
target_compile_options(termux PRIVATE -std=c11 -Wall -Wextra -Werror -Os -fno-stack-protector)
target_link_options(termux PRIVATE -Wl,--gc-sections -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384)
