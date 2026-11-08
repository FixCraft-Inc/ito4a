# Copyright (C) 2026 FixCraft Inc. AGPL-3.0-or-later.
#
# Ito's portable call core. One C++20 library with no Android, JNI or Kotlin
# dependency. Linux builds it with this file directly. Android builds the same
# file with the NDK's CMake toolchain (tools/build_call_core.sh). The shared
# library links stock OpenSSL statically and exports only the C interface.

cmake_minimum_required(VERSION 3.25)
project(ito_call VERSION 0.1.0 LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)
set(CMAKE_VISIBILITY_INLINES_HIDDEN ON)

option(ITO_CALL_WARNINGS_AS_ERRORS "Treat compiler warnings as errors" ON)
set(ITO_CALL_SANITIZE "" CACHE STRING "Sanitizers for every target, such as address,undefined")
option(ITO_CALL_TESTS "Build and register the core's tests" ON)
option(ITO_CALL_FUZZERS "Build libFuzzer parser targets (Clang)" OFF)
option(ITO_CALL_EVIDENCE "Test-only build that exposes secrets for the relay tap" OFF)
set(ITO_CALL_OPENSSL_ROOT "" CACHE PATH
    "Static OpenSSL prefix with include/, lib/libssl.a and lib/libcrypto.a")
set(ITO_CALL_OPENSSL_VERSION "3.5.9" CACHE STRING
    "Required OpenSSL release. Empty accepts the system's 3.5 or later for local checks")
# The Linux endpoint links the public libyume C ABI for its transport.
set(ITO_CALL_YUME_INCLUDE "" CACHE PATH "Directory holding yume/yume.h")
set(ITO_CALL_LIBYUME "" CACHE FILEPATH "Path to libyume.so")

add_compile_options(-Wall -Wextra -Wpedantic -Wshadow -Wconversion -Wsign-conversion
                    -Wnon-virtual-dtor -Wold-style-cast -fstack-protector-strong)
if(ITO_CALL_WARNINGS_AS_ERRORS)
    add_compile_options(-Werror)
endif()
# The NDK toolchain sets its own _FORTIFY_SOURCE level. glibc's needs an
# optimizing build type.
if(NOT ANDROID AND CMAKE_BUILD_TYPE MATCHES "^(Release|RelWithDebInfo|MinSizeRel)$"
   AND NOT ITO_CALL_SANITIZE)
    add_compile_definitions(_FORTIFY_SOURCE=3)
endif()
if(ITO_CALL_SANITIZE)
    add_compile_options(-fsanitize=${ITO_CALL_SANITIZE} -fno-omit-frame-pointer
                        -fno-sanitize-recover=all)
    add_link_options(-fsanitize=${ITO_CALL_SANITIZE})
endif()
if(ITO_CALL_EVIDENCE)
    add_compile_definitions(ITO_CALL_EVIDENCE=1)
endif()
if(ITO_CALL_FUZZERS)
    # The parsers themselves need coverage instrumentation, not only the
    # fuzz entry point.
    add_compile_options(-fsanitize=fuzzer-no-link)
endif()
find_package(Threads REQUIRED)

# Stock OpenSSL. A pinned static prefix for packaging and qualification, or
# the system library for quick local checks when no version is required.
if(ITO_CALL_OPENSSL_ROOT)
    set(_ito_openssl_header "${ITO_CALL_OPENSSL_ROOT}/include/openssl/opensslv.h")
    if(NOT EXISTS "${_ito_openssl_header}")
        message(FATAL_ERROR "${_ito_openssl_header} is missing")
    endif()
    file(STRINGS "${_ito_openssl_header}" _ito_openssl_version_line
         REGEX "^# *define OPENSSL_VERSION_STR ")
    string(REGEX MATCH "\"([0-9.]+)\"" _ito_match "${_ito_openssl_version_line}")
    set(ITO_CALL_OPENSSL_FOUND_VERSION "${CMAKE_MATCH_1}")
    foreach(_ito_component IN ITEMS ssl crypto)
        set(_ito_archive "${ITO_CALL_OPENSSL_ROOT}/lib/lib${_ito_component}.a")
        if(NOT EXISTS "${_ito_archive}")
            message(FATAL_ERROR "${_ito_archive} is missing")
        endif()
        add_library(ito_openssl_${_ito_component} STATIC IMPORTED)
        set_target_properties(ito_openssl_${_ito_component} PROPERTIES
            IMPORTED_LOCATION "${_ito_archive}"
            INTERFACE_INCLUDE_DIRECTORIES "${ITO_CALL_OPENSSL_ROOT}/include")
    endforeach()
    set_property(TARGET ito_openssl_ssl PROPERTY INTERFACE_LINK_LIBRARIES ito_openssl_crypto)
    set_property(TARGET ito_openssl_crypto PROPERTY INTERFACE_LINK_LIBRARIES
                 Threads::Threads ${CMAKE_DL_LIBS})
    set(_ito_openssl_targets ito_openssl_ssl ito_openssl_crypto)
else()
    find_package(OpenSSL 3.5 REQUIRED)
    set(ITO_CALL_OPENSSL_FOUND_VERSION "${OPENSSL_VERSION}")
    set(_ito_openssl_targets OpenSSL::SSL OpenSSL::Crypto)
    message(STATUS "Development build against the system OpenSSL ${OPENSSL_VERSION}, "
                   "not the pinned component")
endif()
if(ITO_CALL_OPENSSL_VERSION AND
   NOT ITO_CALL_OPENSSL_FOUND_VERSION STREQUAL ITO_CALL_OPENSSL_VERSION)
    message(FATAL_ERROR "OpenSSL ${ITO_CALL_OPENSSL_FOUND_VERSION} found, "
                        "${ITO_CALL_OPENSSL_VERSION} required")
endif()

# The core's objects, shared by the packaged library and the tests.
add_library(ito_call_core STATIC
    src/bytes.cpp
    src/codec.cpp
    src/crypto.cpp
    src/driver.cpp
    src/engine.cpp
    src/invitation.cpp
    src/store.cpp
    src/tls.cpp)
target_include_directories(ito_call_core PUBLIC include PRIVATE src)
target_link_libraries(ito_call_core PUBLIC ${_ito_openssl_targets} Threads::Threads)

# libitocall.so: the C interface and nothing else. Static OpenSSL symbols are
# hidden (--exclude-libs) and bound inside the library (-Bsymbolic), so this
# copy never resolves to, or interposes on, another OpenSSL in the process,
# such as the one inside libyume.
set(ITO_CALL_EXPORTS "${CMAKE_CURRENT_SOURCE_DIR}/src/exports.map")
add_library(itocall SHARED src/api.cpp src/content.cpp)
target_include_directories(itocall PRIVATE src)
target_link_libraries(itocall PRIVATE ito_call_core)
target_link_options(itocall PRIVATE
    -Wl,--exclude-libs,ALL -Wl,-Bsymbolic -Wl,--no-undefined
    -Wl,--version-script=${ITO_CALL_EXPORTS} -Wl,-z,relro -Wl,-z,now -Wl,-z,noexecstack)
if(ANDROID)
    target_link_options(itocall PRIVATE -Wl,-z,max-page-size=16384
                        -Wl,-z,common-page-size=16384)
endif()
set_target_properties(itocall PROPERTIES LINK_DEPENDS ${ITO_CALL_EXPORTS})

if(ITO_CALL_YUME_INCLUDE AND ITO_CALL_LIBYUME)
    add_library(ito_yume SHARED IMPORTED)
    set_target_properties(ito_yume PROPERTIES IMPORTED_LOCATION "${ITO_CALL_LIBYUME}"
                          INTERFACE_INCLUDE_DIRECTORIES "${ITO_CALL_YUME_INCLUDE}")
    # The Linux endpoint uses only the two public C interfaces.
    add_executable(ito-call cli/ito_call.cpp cli/host_store.cpp cli/yume_transport.cpp)
    target_link_libraries(ito-call PRIVATE itocall ito_yume Threads::Threads)
    target_include_directories(ito-call PRIVATE include cli)
    target_link_options(ito-call PRIVATE -pie -Wl,-z,relro -Wl,-z,now)
endif()

if(ITO_CALL_TESTS)
    enable_testing()
    foreach(_ito_test IN ITEMS codec crypto store tls engine driver parser_random)
        add_executable(ito_call_${_ito_test}_test tests/${_ito_test}_test.cpp)
        target_link_libraries(ito_call_${_ito_test}_test PRIVATE ito_call_core)
        target_include_directories(ito_call_${_ito_test}_test PRIVATE src tests)
        add_test(NAME ito_call_${_ito_test}_test COMMAND ito_call_${_ito_test}_test)
    endforeach()
    add_executable(ito_call_failure_test tests/failure_test.cpp)
    target_link_libraries(ito_call_failure_test PRIVATE ito_call_core)
    target_include_directories(ito_call_failure_test PRIVATE src tests)
    target_link_options(ito_call_failure_test PRIVATE -Wl,--wrap=RAND_bytes_ex)
    find_package(Python3 REQUIRED COMPONENTS Interpreter)
    add_test(NAME ito_call_failure_test COMMAND ${Python3_EXECUTABLE}
             ${CMAKE_CURRENT_SOURCE_DIR}/tests/failure_sweep.py $<TARGET_FILE:ito_call_failure_test>)
    set_tests_properties(ito_call_failure_test PROPERTIES TIMEOUT 600)
    # The C interface is tested through the packaged library itself.
    add_executable(ito_call_api_test tests/api_test.cpp)
    target_link_libraries(ito_call_api_test PRIVATE itocall Threads::Threads)
    target_include_directories(ito_call_api_test PRIVATE include tests)
    add_test(NAME ito_call_api_test COMMAND ito_call_api_test)
    add_executable(ito_content_test tests/content_test.cpp)
    target_link_libraries(ito_content_test PRIVATE itocall)
    target_include_directories(ito_content_test PRIVATE include tests)
    add_test(NAME ito_content_test COMMAND ito_content_test)
    foreach(_ito_vector_test IN ITEMS codec parser_random)
        target_compile_definitions(ito_call_${_ito_vector_test}_test PRIVATE
            ITO_CALL_VECTORS="${CMAKE_CURRENT_SOURCE_DIR}/tests/vectors.txt")
    endforeach()
    # The exported surface of the packaged library. Only a static OpenSSL
    # build can show that no OpenSSL symbol crosses the library boundary.
    if(ITO_CALL_OPENSSL_ROOT)
        add_test(NAME ito_call_exports_test
                 COMMAND ${CMAKE_COMMAND} -DLIBRARY=$<TARGET_FILE:itocall>
                         -DNM=${CMAKE_NM}
                         -P ${CMAKE_CURRENT_SOURCE_DIR}/tests/check_exports.cmake)
    endif()
endif()

if(ITO_CALL_FUZZERS)
    add_executable(ito_call_parser_fuzzer tests/parser_fuzzer.cpp)
    target_compile_options(ito_call_parser_fuzzer PRIVATE -fsanitize=fuzzer)
    target_link_options(ito_call_parser_fuzzer PRIVATE -fsanitize=fuzzer)
    target_link_libraries(ito_call_parser_fuzzer PRIVATE ito_call_core)
    target_include_directories(ito_call_parser_fuzzer PRIVATE src)
endif()
