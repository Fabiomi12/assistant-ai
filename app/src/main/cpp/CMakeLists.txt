# app/src/main/cpp/CMakeLists.txt
cmake_minimum_required(VERSION 3.15)
project(llama_jni)

# Require C++17
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# Android specific settings
set(CMAKE_ANDROID_STL_TYPE c++_shared)
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -frtti -fexceptions -pthread")

# Find required packages
find_package(Threads REQUIRED)

# === llama.cpp configuration ===
# Disable mmap on Android (avoids POSIX_MADV_WILLNEED errors)
set(LLAMA_NO_MMAP ON CACHE BOOL "Disable llama-mmap on Android" FORCE)

# === llama.cpp configuration ===
# Set llama.cpp build options before adding subdirectory
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "llama: build tests" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "llama: build examples" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "llama: build server" FORCE)
set(GGML_BACKEND_SHARED ON CACHE BOOL "ggml: build shared backend" FORCE)
set(GGML_SHARED ON CACHE BOOL "ggml: build shared library" FORCE)

# Add llama.cpp as a subdirectory
add_subdirectory(llama.cpp llama_cpp_build)

# === JNI library ===
# Build our JNI bridge
add_library(llama_jni SHARED llama_jni.cpp)

# Find Android log library
find_library(log-lib log)

# Include paths for llama.cpp headers
target_include_directories(llama_jni PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/include
        ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/src
        ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/ggml/include
)

# Set compile options
target_compile_options(llama_jni PRIVATE
        -pthread
        -fPIC
        -DGGML_USE_CPU=1
)

# Link libraries in the correct order
target_link_libraries(llama_jni
        llama                    # from llama.cpp (must come first)
        ggml                     # ggml library
        Threads::Threads         # Use the imported target for threads
        ${log-lib}               # Android logging
        android                  # Android system
        m                        # math library
        dl                       # dynamic loading
)

# Set target properties
set_target_properties(llama_jni PROPERTIES
        CXX_STANDARD 17
        CXX_STANDARD_REQUIRED ON
        CXX_EXTENSIONS OFF
)