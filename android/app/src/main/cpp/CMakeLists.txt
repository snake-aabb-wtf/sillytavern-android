cmake_minimum_required(VERSION 3.22.1)

project(stbridge LANGUAGES CXX)

# std::string::data() used for argv requires non-const char* (C++17). Do not rely on
# whatever default the NDK toolchain happens to pick.
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Prebuilt nodejs-mobile runtime. Downloaded by CI into the gitignored
# android/nodejs-mobile/ directory; never committed.
set(LIBNODE_DIR "${CMAKE_CURRENT_LIST_DIR}/../../../../nodejs-mobile/libnode")

add_library(stbridge SHARED jni_bridge.cpp)

target_include_directories(stbridge PRIVATE "${LIBNODE_DIR}/include/node")

add_library(node SHARED IMPORTED)
set_target_properties(node PROPERTIES
    IMPORTED_LOCATION "${LIBNODE_DIR}/bin/${ANDROID_ABI}/libnode.so")

find_library(log-lib log)

# libnode.so depends on libc++_shared.so, so the app must use the shared STL too.
target_link_libraries(stbridge node ${log-lib})
