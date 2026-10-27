# Copyright (C) 2026 FixCraft Inc. AGPL-3.0-or-later.
#
# The call relay is a host service, separate from the Android application and
# its Gradle build. It links only the C++ standard library.

cmake_minimum_required(VERSION 3.25)
project(ito_call_relay VERSION 0.1.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

if(NOT CMAKE_SYSTEM_NAME STREQUAL "Linux")
    message(FATAL_ERROR "ito-call-relay uses epoll, signalfd and SO_PEERCRED and builds on Linux only")
endif()

option(ITO_RELAY_WARNINGS_AS_ERRORS "Treat compiler warnings as errors" ON)
set(ITO_RELAY_SANITIZE "" CACHE STRING "Sanitizers for every target, such as address,undefined")
option(ITO_RELAY_TESTS "Build and register the relay tests" ON)

add_compile_options(-Wall -Wextra -Wpedantic -Wshadow -Wconversion -Wsign-conversion
                    -Wnon-virtual-dtor -Wold-style-cast -fstack-protector-strong)
if(ITO_RELAY_WARNINGS_AS_ERRORS)
    add_compile_options(-Werror)
endif()
if(NOT CMAKE_BUILD_TYPE STREQUAL "Debug" AND NOT ITO_RELAY_SANITIZE)
    add_compile_definitions(_FORTIFY_SOURCE=3)
endif()
add_link_options(-Wl,-z,relro -Wl,-z,now -Wl,-z,noexecstack)
if(ITO_RELAY_SANITIZE)
    add_compile_options(-fsanitize=${ITO_RELAY_SANITIZE} -fno-omit-frame-pointer
                        -fno-sanitize-recover=all)
    add_link_options(-fsanitize=${ITO_RELAY_SANITIZE})
endif()

# Parsing, room policy, the event loop and the listener trust boundary. The
# program and the tests link the same objects.
add_library(ito_relay_core STATIC
    src/listener.cpp
    src/relay.cpp
    src/room_table.cpp
    src/wire.cpp)
target_include_directories(ito_relay_core PUBLIC src)

add_executable(ito-call-relay src/main.cpp)
target_link_libraries(ito-call-relay PRIVATE ito_relay_core)
set_target_properties(ito-call-relay PROPERTIES POSITION_INDEPENDENT_CODE ON)
target_link_options(ito-call-relay PRIVATE -pie)

if(ITO_RELAY_TESTS)
    enable_testing()
    find_package(Python3 3.11 REQUIRED COMPONENTS Interpreter)
    foreach(_ito_relay_test IN ITEMS wire room_table)
        add_executable(ito_relay_${_ito_relay_test}_test tests/${_ito_relay_test}_test.cpp)
        target_link_libraries(ito_relay_${_ito_relay_test}_test PRIVATE ito_relay_core)
        add_test(NAME ito_relay_${_ito_relay_test}_test
                 COMMAND ito_relay_${_ito_relay_test}_test)
    endforeach()
    add_executable(ito_relay_loop_test tests/loop_test.cpp)
    target_link_libraries(ito_relay_loop_test PRIVATE ito_relay_core)
    add_test(NAME ito_relay_loop_test COMMAND ito_relay_loop_test)
    # The real program against synthetic module peers, with the version-1
    # default limits. -B keeps bytecode out of the source tree.
    add_test(NAME ito_relay_component_test
             COMMAND Python3::Interpreter -B
                     ${CMAKE_CURRENT_SOURCE_DIR}/tests/component_test.py
                     --relay $<TARGET_FILE:ito-call-relay>)
    set_tests_properties(ito_relay_component_test PROPERTIES TIMEOUT 600)
endif()
