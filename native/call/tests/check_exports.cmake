# Copyright (C) 2026 FixCraft Inc. AGPL-3.0-or-later.
#
# cmake -DLIBRARY=libitocall.so -DNM=nm -P check_exports.cmake
#
# The packaged library defines only ito_* functions in its dynamic symbol
# table and needs no OpenSSL symbol from anywhere else. Its OpenSSL copy can
# then neither interpose on nor bind to another OpenSSL in the process.

foreach(_required IN ITEMS LIBRARY NM)
    if(NOT ${_required})
        message(FATAL_ERROR "${_required} is required")
    endif()
endforeach()

execute_process(COMMAND ${NM} -D --defined-only ${LIBRARY}
                OUTPUT_VARIABLE _defined RESULT_VARIABLE _status)
if(NOT _status EQUAL 0)
    message(FATAL_ERROR "nm failed on ${LIBRARY}")
endif()
string(REPLACE "\n" ";" _lines "${_defined}")
set(_exports 0)
foreach(_line IN LISTS _lines)
    if(_line STREQUAL "")
        continue()
    endif()
    string(REGEX REPLACE "^.* ([^ ]+)$" "\\1" _symbol "${_line}")
    # nm appends the symbol version, such as @@ITO_CALL_1.
    string(REGEX REPLACE "@.*$" "" _symbol "${_symbol}")
    # The version node of exports.map is the one non-function definition.
    if(_symbol STREQUAL "ITO_CALL_1")
        continue()
    endif()
    if(NOT _symbol MATCHES "^ito_[a-z_]+$")
        message(FATAL_ERROR "unexpected export: ${_symbol}")
    endif()
    math(EXPR _exports "${_exports} + 1")
endforeach()
if(_exports LESS 30)
    message(FATAL_ERROR "only ${_exports} ito_ exports")
endif()

execute_process(COMMAND ${NM} -D --undefined-only ${LIBRARY}
                OUTPUT_VARIABLE _undefined)
string(REGEX MATCHALL "[A-Za-z0-9_]+" _names "${_undefined}")
foreach(_symbol IN LISTS _names)
    if(_symbol MATCHES "^(SSL|EVP|OPENSSL|OSSL|CRYPTO|ERR|BIO|X509|RAND|ossl)_")
        message(FATAL_ERROR "OpenSSL symbol imported: ${_symbol}")
    endif()
endforeach()
message(STATUS "${_exports} ito_ exports, no OpenSSL import")
