cmake_minimum_required(VERSION 3.20)
project(sctp_fstack LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

option(SCTP_HAVE_LKSCTP "Build the explicit Linux-kernel SCTP test/lab path" ON)
option(SCTP_HAVE_FSTACK_DPDK "Build validated F-Stack/DPDK SCTP (never lksctp)" OFF)
set(FSTACK_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/vendor/f-stack" CACHE PATH "Pinned full F-Stack source")

set(SCTP_FS_SOURCES
        src/sctp_facade.cpp
        src/fstack_glue.cpp)

add_library(sctp_fstack SHARED ${SCTP_FS_SOURCES})
target_include_directories(sctp_fstack PUBLIC include)
set_target_properties(sctp_fstack PROPERTIES OUTPUT_NAME sctp_fstack)

add_executable(sctp-fstack src/sidecar_main.cpp ${SCTP_FS_SOURCES})
target_include_directories(sctp-fstack PRIVATE include)

if(DEFINED SCTP_HAVE_DPDK AND SCTP_HAVE_DPDK)
    message(FATAL_ERROR "SCTP_HAVE_DPDK was an unsafe capability-only flag. "
            "Use SCTP_HAVE_FSTACK_DPDK=ON; it validates and links the real implementation.")
endif()

if(DEFINED SCTP_HAVE_FSTACK AND SCTP_HAVE_FSTACK)
    message(FATAL_ERROR "SCTP_HAVE_FSTACK formerly meant lksctp. Use SCTP_HAVE_LKSCTP=ON explicitly.")
endif()

if(SCTP_HAVE_LKSCTP)
    find_library(SCTP_LIB sctp)
    if(NOT SCTP_LIB)
        message(FATAL_ERROR "SCTP_HAVE_LKSCTP=ON requires libsctp (lksctp-tools).")
    endif()
    target_compile_definitions(sctp_fstack PRIVATE SCTP_HAVE_LKSCTP)
    target_compile_definitions(sctp-fstack PRIVATE SCTP_HAVE_LKSCTP)
    target_link_libraries(sctp_fstack PRIVATE ${SCTP_LIB})
    target_link_libraries(sctp-fstack PRIVATE ${SCTP_LIB})
endif()

if(SCTP_HAVE_FSTACK_DPDK)
    execute_process(
        COMMAND "${CMAKE_CURRENT_SOURCE_DIR}/scripts/validate-fstack-sctp.sh"
                "${FSTACK_ROOT}" "${FSTACK_ROOT}/lib/libfstack.a"
        RESULT_VARIABLE FSTACK_SCTP_GATE
        OUTPUT_VARIABLE FSTACK_SCTP_GATE_OUT
        ERROR_VARIABLE FSTACK_SCTP_GATE_ERR)
    if(NOT FSTACK_SCTP_GATE EQUAL 0)
        message(FATAL_ERROR "F-Stack SCTP source gate failed:\n${FSTACK_SCTP_GATE_OUT}${FSTACK_SCTP_GATE_ERR}")
    endif()
    find_package(PkgConfig REQUIRED)
    pkg_check_modules(DPDK REQUIRED IMPORTED_TARGET libdpdk)
    find_package(OpenSSL REQUIRED)
    find_package(Threads REQUIRED)
    list(GET DPDK_LIBRARY_DIRS 0 DPDK_PRIMARY_LIBRARY_DIR)
    find_library(FSTACK_LIB fstack PATHS "${FSTACK_ROOT}/lib" NO_DEFAULT_PATH REQUIRED)
    find_library(DPDK_BOND_LIB rte_net_bond PATHS ${DPDK_LIBRARY_DIRS} REQUIRED)
    foreach(target sctp_fstack sctp-fstack)
        target_compile_definitions(${target} PRIVATE SCTP_HAVE_FSTACK_DPDK)
        target_include_directories(${target} PRIVATE "${FSTACK_ROOT}/lib")
        target_link_libraries(${target} PRIVATE "${FSTACK_LIB}" "${DPDK_BOND_LIB}" PkgConfig::DPDK
                OpenSSL::Crypto Threads::Threads ${CMAKE_DL_LIBS})
        target_link_options(${target} PRIVATE "-Wl,-rpath-link,${DPDK_PRIMARY_LIBRARY_DIR}")
        set_property(TARGET ${target} PROPERTY BUILD_RPATH "${DPDK_PRIMARY_LIBRARY_DIR}")
    endforeach()
endif()

add_executable(sctp_fs_tests tests/test_sctp_fs.cpp)
target_include_directories(sctp_fs_tests PRIVATE include)
target_link_libraries(sctp_fs_tests PRIVATE sctp_fstack)
if(SCTP_HAVE_LKSCTP)
    target_compile_definitions(sctp_fs_tests PRIVATE SCTP_HAVE_LKSCTP)
endif()
if(SCTP_HAVE_FSTACK_DPDK)
    target_compile_definitions(sctp_fs_tests PRIVATE SCTP_HAVE_FSTACK_DPDK)
    target_link_options(sctp_fs_tests PRIVATE "-Wl,-rpath-link,${DPDK_PRIMARY_LIBRARY_DIR}")
    set_property(TARGET sctp_fs_tests PROPERTY BUILD_RPATH "${DPDK_PRIMARY_LIBRARY_DIR}")
endif()

enable_testing()
add_test(NAME sctp_fs_tests COMMAND sctp_fs_tests)
if(SCTP_HAVE_FSTACK_DPDK)
    add_test(NAME fstack_sctp_archive_gate
            COMMAND bash "${CMAKE_CURRENT_SOURCE_DIR}/tests/test_validate_fstack_sctp.sh"
                    "${FSTACK_ROOT}" "${FSTACK_LIB}")
endif()

install(TARGETS sctp_fstack sctp-fstack
        RUNTIME DESTINATION bin
        LIBRARY DESTINATION lib)
install(FILES include/sctp_abi.h include/sctp_ring.h DESTINATION include)
