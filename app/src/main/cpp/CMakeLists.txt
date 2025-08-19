cmake_minimum_required(VERSION 3.15)
project(llama_jni)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

if(NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release)
endif()
set(CMAKE_C_FLAGS_RELEASE   "${CMAKE_C_FLAGS_RELEASE} -O3 -DNDEBUG")
set(CMAKE_CXX_FLAGS_RELEASE "${CMAKE_CXX_FLAGS_RELEASE} -O3 -DNDEBUG")

set(CMAKE_ANDROID_STL_TYPE c++_shared)

# --- Point to your vendored llama.cpp directory (adjust if you used a different path)
set(LLAMA_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/llama.cpp)

# 16 KB page-size
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384")
set(CMAKE_EXE_LINKER_FLAGS    "${CMAKE_EXE_LINKER_FLAGS}    -Wl,-z,max-page-size=16384")

# ggml/llama toggles
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_USE_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_THREADPOOL ON CACHE BOOL "" FORCE)
set(GGML_BLAS OFF CACHE BOOL "" FORCE)
set(GGML_SHARED OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_SHARED OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)

# Export compile commands so the IDE indexer has an authoritative database
set(CMAKE_EXPORT_COMPILE_COMMANDS ON)

# Bring in llama.cpp (this creates target 'llama')
add_subdirectory(${LLAMA_DIR} ${CMAKE_CURRENT_BINARY_DIR}/llama_cpp_build)

# Your JNI library target that OWNS llama_jni.cpp
add_library(llama_jni SHARED
        ${CMAKE_CURRENT_SOURCE_DIR}/llama_jni.cpp
)

# Headers the IDE must see to resolve includes
target_include_directories(llama_jni PRIVATE
        ${LLAMA_DIR}
        ${LLAMA_DIR}/include
        ${LLAMA_DIR}/src
        ${LLAMA_DIR}/ggml/include
)

# Compile / link options
target_compile_options(llama_jni PRIVATE -fPIC -pthread -DGGML_USE_CPU=1)
target_link_options(llama_jni PRIVATE "LINKER:-z,max-page-size=16384" -pthread)

find_library(log-lib log)

target_link_libraries(llama_jni
        llama
        ${log-lib}
        android
        m
        dl
)

# (Optional) propagate 16KB flag to llama too
if(TARGET llama)
    set_target_properties(llama PROPERTIES LINK_FLAGS "-Wl,-z,max-page-size=16384")
endif()
