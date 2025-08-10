# app/src/main/cpp/CMakeLists.txt
cmake_minimum_required(VERSION 3.15)
project(llama_jni)

# Require C++17
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# Android specific settings
set(CMAKE_ANDROID_STL_TYPE c++_shared)

# === 16KB page size compatibility ===
# Add linker flags for 16KB alignment
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384")
set(CMAKE_EXE_LINKER_FLAGS "${CMAKE_EXE_LINKER_FLAGS} -Wl,-z,max-page-size=16384")

# Set C++ flags with alignment and other optimizations
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -frtti -fexceptions -pthread")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -Wl,-z,max-page-size=16384")

# Find required packages
find_package(Threads REQUIRED)

# === llama.cpp configuration ===
# Disable mmap on Android (avoids POSIX_MADV_WILLNEED errors)
set(LLAMA_NO_MMAP ON CACHE BOOL "Disable llama-mmap on Android" FORCE)

# Set llama.cpp build options before adding subdirectory
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "llama: build tests" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "llama: build examples" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "llama: build server" FORCE)
set(GGML_BACKEND_SHARED ON CACHE BOOL "ggml: build shared backend" FORCE)
set(GGML_SHARED ON CACHE BOOL "ggml: build shared library" FORCE)

# Add 16KB alignment flags to llama.cpp build
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -Wl,-z,max-page-size=16384")

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

# Set compile options with 16KB alignment
target_compile_options(llama_jni PRIVATE
        -pthread
        -fPIC
        -DGGML_USE_CPU=1
)

# Set linker flags for 16KB alignment on the main library
target_link_options(llama_jni PRIVATE
        "LINKER:-z,max-page-size=16384"
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

# Set target properties with 16KB alignment
set_target_properties(llama_jni PROPERTIES
        CXX_STANDARD 17
        CXX_STANDARD_REQUIRED ON
        CXX_EXTENSIONS OFF
        LINK_FLAGS "-Wl,-z,max-page-size=16384"
)

# Apply 16KB alignment to llama.cpp libraries as well
if(TARGET llama)
    set_target_properties(llama PROPERTIES
            LINK_FLAGS "-Wl,-z,max-page-size=16384"
    )
endif()

if(TARGET ggml)
    set_target_properties(ggml PROPERTIES
            LINK_FLAGS "-Wl,-z,max-page-size=16384"
    )
endif()