cmake_minimum_required(VERSION 3.20)

project("eXtreme Compressor" VERSION 0.1 LANGUAGES C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_C_EXTENSIONS OFF)

add_executable(extc src/main.c)

if(MSVC)
    target_compile_options(extc PRIVATE /W4)
else()
    target_compile_options(extc PRIVATE -Wall -Wextra -Wpedantic)
endif()
