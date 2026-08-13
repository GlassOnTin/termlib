cmake_minimum_required(VERSION 3.18.1)

project("cb_term")

# libvterm static library
add_library(vterm STATIC
    ${CMAKE_CURRENT_SOURCE_DIR}/libvterm/src/encoding.c
    ${CMAKE_CURRENT_SOURCE_DIR}/libvterm/src/keyboard.c
    ${CMAKE_CURRENT_SOURCE_DIR}/libvterm/src/mouse.c
    ${CMAKE_CURRENT_SOURCE_DIR}/libvterm/src/parser.c
    ${CMAKE_CURRENT_SOURCE_DIR}/libvterm/src/pen.c
    ${CMAKE_CURRENT_SOURCE_DIR}/libvterm/src/screen.c
    ${CMAKE_CURRENT_SOURCE_DIR}/libvterm/src/state.c
    ${CMAKE_CURRENT_SOURCE_DIR}/libvterm/src/unicode.c
    ${CMAKE_CURRENT_SOURCE_DIR}/libvterm/src/vterm.c
)

target_include_directories(vterm PUBLIC
    ${CMAKE_CURRENT_SOURCE_DIR}/libvterm/include
)

target_compile_definitions(vterm PRIVATE
    VTERM_STATIC
)

set_target_properties(vterm PROPERTIES POSITION_INDEPENDENT_CODE ON)

# JNI wrapper library
add_library(jni_cb_term SHARED
    ${CMAKE_CURRENT_SOURCE_DIR}/Terminal.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/mutf8.cpp
)

target_include_directories(jni_cb_term PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/libvterm/include
)

target_compile_features(jni_cb_term PRIVATE cxx_std_17)

if(ANDROID)
    target_link_options(jni_cb_term PRIVATE "-Wl,-z,max-page-size=16384")

    target_link_libraries(jni_cb_term
        vterm
        android
        log
    )
else()
    find_package(JNI REQUIRED)
    target_include_directories(jni_cb_term PRIVATE ${JNI_INCLUDE_DIRS})
    target_link_libraries(jni_cb_term
        vterm
    )
endif()

# Host-only ASan crash-regression tests (#533). ASan is what makes these a
# real gate: the reflow underflow reads BEFORE the screen buffer, which on a
# plain malloc heap usually returns slop instead of faulting — a JVM-level
# test provably passed on the broken code. Never built for Android.
option(CB_TERM_HOST_TESTS "Build host ASan regression tests" OFF)
if(CB_TERM_HOST_TESTS)
    add_library(vterm_asan STATIC
        ${CMAKE_CURRENT_SOURCE_DIR}/libvterm/src/encoding.c
        ${CMAKE_CURRENT_SOURCE_DIR}/libvterm/src/keyboard.c
        ${CMAKE_CURRENT_SOURCE_DIR}/libvterm/src/mouse.c
        ${CMAKE_CURRENT_SOURCE_DIR}/libvterm/src/parser.c
        ${CMAKE_CURRENT_SOURCE_DIR}/libvterm/src/pen.c
        ${CMAKE_CURRENT_SOURCE_DIR}/libvterm/src/screen.c
        ${CMAKE_CURRENT_SOURCE_DIR}/libvterm/src/state.c
        ${CMAKE_CURRENT_SOURCE_DIR}/libvterm/src/unicode.c
        ${CMAKE_CURRENT_SOURCE_DIR}/libvterm/src/vterm.c
    )
    target_include_directories(vterm_asan PUBLIC
        ${CMAKE_CURRENT_SOURCE_DIR}/libvterm/include
    )
    target_compile_definitions(vterm_asan PRIVATE VTERM_STATIC)
    target_compile_options(vterm_asan PRIVATE -fsanitize=address -g -O1)

    add_executable(reflow_underflow_test
        ${CMAKE_CURRENT_SOURCE_DIR}/tests/reflow_underflow_test.c
    )
    target_compile_options(reflow_underflow_test PRIVATE -fsanitize=address -g -O1)
    target_link_options(reflow_underflow_test PRIVATE -fsanitize=address)
    target_link_libraries(reflow_underflow_test vterm_asan)
endif()
