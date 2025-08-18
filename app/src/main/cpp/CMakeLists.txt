cmake_minimum_required(VERSION 3.15)
project(llama_jni)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# Prefer Release for native code (big speedup)
if(NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release)
endif()
set(CMAKE_C_FLAGS_RELEASE   "${CMAKE_C_FLAGS_RELEASE} -O3 -DNDEBUG")
set(CMAKE_CXX_FLAGS_RELEASE "${CMAKE_CXX_FLAGS_RELEASE} -O3 -DNDEBUG")

set(CMAKE_ANDROID_STL_TYPE c++_shared)

# 16 KB page-size (Android 15+)
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384")
set(CMAKE_EXE_LINKER_FLAGS    "${CMAKE_EXE_LINKER_FLAGS}    -Wl,-z,max-page-size=16384")

# llama.cpp / ggml options (CPU NEON path; fast/stable on phone)
set(LLAMA_NO_MMAP OFF CACHE BOOL "Disable llama-mmap" FORCE)
set(GGML_BLAS OFF CACHE BOOL "ggml: use BLAS" FORCE)
set(GGML_SHARED OFF CACHE BOOL "ggml: build shared lib" FORCE)
set(GGML_BACKEND_SHARED OFF CACHE BOOL "ggml: build shared backend" FORCE)
# add/keep these toggles:
set(GGML_OPENMP OFF CACHE BOOL "ggml: disable OpenMP" FORCE)
set(GGML_USE_OPENMP OFF CACHE BOOL "ggml: disable OpenMP (legacy toggle)" FORCE)
set(GGML_THREADPOOL ON CACHE BOOL "ggml: enable internal threadpool" FORCE)

set(LLAMA_BUILD_TESTS OFF CACHE BOOL "llama: build tests" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "llama: build examples" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "llama: build server" FORCE)

# Bring in llama.cpp (assumes it's at src/main/cpp/llama.cpp)
add_subdirectory(llama.cpp llama_cpp_build)

# Our JNI bridge
add_library(llama_jni SHARED llama_jni.cpp)

# Android system libs
find_library(log-lib log)

# Headers
target_include_directories(llama_jni PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/include
        ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/src
        ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/ggml/include
)

# Compile options (add pthread here; no need for Threads::Threads target)
target_compile_options(llama_jni PRIVATE -fPIC -pthread -DGGML_USE_CPU=1)

# Linker options
target_link_options(llama_jni PRIVATE "LINKER:-z,max-page-size=16384" -pthread)

# Link order: llama first; ggml is linked statically by llama when GGML_SHARED=OFF
target_link_libraries(llama_jni
        llama
        ${log-lib}
        android
        m
        dl
)

# (Optional) propagate 16KB flag to llama target too
if(TARGET llama)
    set_target_properties(llama PROPERTIES LINK_FLAGS "-Wl,-z,max-page-size=16384")
endif()
