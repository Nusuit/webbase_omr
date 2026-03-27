cmake_minimum_required(VERSION 3.20)
project(omr_wasm LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(OpenCV_DIR "${CMAKE_SOURCE_DIR}/../opencv_wasm_build")
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


set_target_properties(omr PROPERTIES
  OUTPUT_NAME "omr"
  SUFFIX ".js"
  RUNTIME_OUTPUT_DIRECTORY "${CMAKE_SOURCE_DIR}/web/wasm"
)

target_link_options(omr PRIVATE
  "SHELL:-O3"
  "SHELL:-sWASM=1"
  "SHELL:-sALLOW_MEMORY_GROWTH=1"
  "SHELL:-sMODULARIZE=1"
  "SHELL:-sEXPORT_NAME=OmrModule"
  "SHELL:-sENVIRONMENT=web,worker"
  "SHELL:-sFILESYSTEM=0"
  "SHELL:-sEXPORTED_FUNCTIONS=['_omr_process_frame','_omr_process_frame_v2','_omr_process_sheet','_omr_get_last_preview','_omr_get_last_warped','_malloc','_free']"
  "SHELL:-sEXPORTED_RUNTIME_METHODS=['ccall','cwrap','HEAPU8','HEAP32']"
)
