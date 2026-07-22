cmake_minimum_required(VERSION 3.22.1)
project(subprocess C)

set(CMAKE_C_STANDARD 11)

add_library(subprocess SHARED subprocess.c)

find_library(log-lib log)

target_link_libraries(subprocess ${log-lib})
