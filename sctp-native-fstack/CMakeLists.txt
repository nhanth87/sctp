cmake_minimum_required(VERSION 3.20)
project(sctp_fstack LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

option(SCTP_HAVE_FSTACK "Default SCTP sockets via lksctp (ON). freebsd/ is not shipped." ON)
option(SCTP_HAVE_DPDK "Link DPDK and advertise CAP_DPDK (off until a NIC gate is green)" OFF)

set(SCTP_FS_SOURCES
        src/sctp_facade.cpp
        src/fstack_glue.cpp)

add_library(sctp_fstack SHARED ${SCTP_FS_SOURCES})
target_include_directories(sctp_fstack PUBLIC include)
set_target_properties(sctp_fstack PROPERTIES OUTPUT_NAME sctp_fstack)

add_executable(sctp-fstack src/sidecar_main.cpp ${SCTP_FS_SOURCES})
target_include_directories(sctp-fstack PRIVATE include)

if(SCTP_HAVE_FSTACK)
    find_library(SCTP_LIB sctp)
    if(NOT SCTP_LIB)
        message(FATAL_ERROR "SCTP_HAVE_FSTACK=ON requires libsctp (lksctp-tools). "
                "Install it or pass -DSCTP_HAVE_FSTACK=OFF (Netty becomes the only JVM path).")
    endif()
    target_compile_definitions(sctp_fstack PRIVATE SCTP_HAVE_FSTACK)
    target_compile_definitions(sctp-fstack PRIVATE SCTP_HAVE_FSTACK)
    target_link_libraries(sctp_fstack PRIVATE ${SCTP_LIB})
    target_link_libraries(sctp-fstack PRIVATE ${SCTP_LIB})
endif()

if(SCTP_HAVE_DPDK)
    target_compile_definitions(sctp_fstack PRIVATE SCTP_HAVE_DPDK)
    target_compile_definitions(sctp-fstack PRIVATE SCTP_HAVE_DPDK)
endif()

add_executable(sctp_fs_tests tests/test_sctp_fs.cpp)
target_include_directories(sctp_fs_tests PRIVATE include)
target_link_libraries(sctp_fs_tests PRIVATE sctp_fstack)
if(SCTP_HAVE_FSTACK)
    target_compile_definitions(sctp_fs_tests PRIVATE SCTP_HAVE_FSTACK)
endif()
if(SCTP_HAVE_DPDK)
    target_compile_definitions(sctp_fs_tests PRIVATE SCTP_HAVE_DPDK)
endif()

enable_testing()
add_test(NAME sctp_fs_tests COMMAND sctp_fs_tests)

install(TARGETS sctp_fstack sctp-fstack
        RUNTIME DESTINATION bin
        LIBRARY DESTINATION lib)
install(FILES include/sctp_abi.h include/sctp_ring.h DESTINATION include)
