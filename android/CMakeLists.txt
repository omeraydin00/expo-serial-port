cmake_minimum_required(VERSION 3.10)
project(exposerialport C)

add_library(exposerialport SHARED src/main/cpp/termios.c)
target_compile_options(exposerialport PRIVATE -Wall -Werror)
