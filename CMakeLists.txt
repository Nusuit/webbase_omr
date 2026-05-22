cmake_minimum_required(VERSION 3.20)
project(omr_wasm LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# --- Build variant selection ---
# OPENCV_VARIANT: baseline | simd | threads
# baseline → ../opencv_wasm_build   (existing, no forced SIMD in OpenCV)
# simd     → ../opencv_wasm_simd    (OpenCV rebuilt with -msimd128)
# threads  → ../opencv_wasm_threads (OpenCV rebuilt with -msimd128 -pthread)
set(OPENCV_VARIANT "baseline" CACHE STRING "OpenCV build variant: baseline | simd | threads")
option(ENABLE_THREADS "Add Emscripten pthread support to the omr module" OFF)

if(OPENCV_VARIANT STREQUAL "simd")
  set(OpenCV_DIR "${CMAKE_SOURCE_DIR}/../opencv_wasm_simd")
  set(OMR_OUTPUT_NAME "omr_simd")
elseif(OPENCV_VARIANT STREQUAL "threads")
  set(OpenCV_DIR "${CMAKE_SOURCE_DIR}/../opencv_wasm_threads")
  set(OMR_OUTPUT_NAME "omr_threads")
  set(ENABLE_THREADS ON)
else()
  set(OpenCV_DIR "${CMAKE_SOURCE_DIR}/../opencv_wasm_build")
  set(OMR_OUTPUT_NAME "omr")
endif()

find_package(OpenCV REQUIRED)

add_executable(
  omr
  src/main.cpp
  src/core/omr_core.cpp
  src/core/omr_bindings.cpp
  src/core/omr_warp.cpp
)

target_include_directories(omr PRIVATE "${CMAKE_SOURCE_DIR}/src" ${OpenCV_INCLUDE_DIRS})
target_link_libraries(omr PRIVATE ${OpenCV_LIBS})

target_compile_options(omr PRIVATE
  "SHELL:-O3"
  "SHELL:-msimd128"
)

if(ENABLE_THREADS)
  target_compile_options(omr PRIVATE "SHELL:-pthread")
endif()

set_target_properties(omr PROPERTIES
  OUTPUT_NAME "${OMR_OUTPUT_NAME}"
  SUFFIX ".js"
  RUNTIME_OUTPUT_DIRECTORY "${CMAKE_SOURCE_DIR}/web/wasm"
)

set(OMR_LINK_OPTS
  "SHELL:-O3"
  "SHELL:-msimd128"
  "SHELL:-sWASM=1"
  "SHELL:-sALLOW_MEMORY_GROWTH=1"
  "SHELL:-sMODULARIZE=1"
  "SHELL:-sEXPORT_NAME=OmrModule"
  "SHELL:-sENVIRONMENT=web,worker"
  "SHELL:-sFILESYSTEM=0"
  "SHELL:-sEXPORTED_FUNCTIONS=['_omr_process_frame','_omr_process_frame_v2','_omr_process_sheet','_omr_get_last_preview','_omr_get_last_warped','_omr_clear_previews','_malloc','_free']"
  "SHELL:-sEXPORTED_RUNTIME_METHODS=['ccall','cwrap','HEAPU8','HEAP32']"
)

if(ENABLE_THREADS)
  list(APPEND OMR_LINK_OPTS
    "SHELL:-sUSE_PTHREADS=1"
    "SHELL:-sPTHREAD_POOL_SIZE=4"
  )
endif()

target_link_options(omr PRIVATE ${OMR_LINK_OPTS})
