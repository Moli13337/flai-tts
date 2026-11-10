cmake_minimum_required(VERSION 3.22)
project(flai C)

file(GLOB LAME_SRC ${CMAKE_CURRENT_SOURCE_DIR}/lame/*.c)

add_library(mp3lame STATIC ${LAME_SRC})
target_include_directories(mp3lame PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/lame)
target_compile_definitions(mp3lame PRIVATE HAVE_CONFIG_H)
target_compile_options(mp3lame PRIVATE -O2 -w -Wno-error=implicit-function-declaration -Wno-implicit-function-declaration)

add_library(lamejni SHARED lame_jni.c)
target_link_libraries(lamejni PRIVATE mp3lame log)
