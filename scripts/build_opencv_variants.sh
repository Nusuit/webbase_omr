#!/usr/bin/env bash
# Build OpenCV as Emscripten static libraries in two variants:
#   ../opencv_wasm_simd    — SIMD128 only
#   ../opencv_wasm_threads — SIMD128 + pthreads (requires SharedArrayBuffer headers)
#
# Requirements: WSL2 / Linux, Emscripten SDK installed at $HOME/emsdk (or set $EMSDK)
# Run from project root: bash scripts/build_opencv_variants.sh

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OPENCV_SRC="${OPENCV_SRC:-$SCRIPT_DIR/../opencv}"
EMSDK="${EMSDK:-$HOME/emsdk}"

if [ ! -f "$EMSDK/emsdk_env.sh" ]; then
  echo "ERROR: Emscripten SDK not found at $EMSDK"
  echo "Install it: git clone https://github.com/emscripten-core/emsdk.git ~/emsdk && cd ~/emsdk && ./emsdk install latest && ./emsdk activate latest"
  exit 1
fi

source "$EMSDK/emsdk_env.sh"
echo "Emscripten: $(emcc --version | head -1)"

# Clone OpenCV once when a local source checkout is not available.
if [ ! -d "$OPENCV_SRC" ]; then
  OPENCV_SRC="$SCRIPT_DIR/../opencv_src"
  echo "Cloning OpenCV 4.10.0..."
  git clone https://github.com/opencv/opencv.git --branch 4.10.0 --depth 1 "$OPENCV_SRC"
fi

COMMON_CMAKE_ARGS=(
  -DCMAKE_BUILD_TYPE=Release
  -DENABLE_PIC=FALSE
  -DCPU_BASELINE=
  -DCPU_DISPATCH=
  -DCV_TRACE=OFF
  -DBUILD_LIST=core,imgproc,calib3d
  -DBUILD_SHARED_LIBS=OFF
  -DBUILD_TESTS=OFF
  -DBUILD_PERF_TESTS=OFF
  -DBUILD_opencv_apps=OFF
  -DBUILD_opencv_python3=OFF
  -DBUILD_opencv_python2=OFF
  -DWITH_JPEG=OFF
  -DWITH_OPENJPEG=OFF
  -DWITH_PNG=OFF
  -DWITH_PROTOBUF=OFF
  -DWITH_TIFF=OFF
  -DWITH_LAPACK=OFF
  -DBUILD_OPENJPEG=OFF
  -DBUILD_PROTOBUF=OFF
)

# --- Variant A: SIMD only ---
SIMD_OUT="$SCRIPT_DIR/../opencv_wasm_simd"
echo ""
echo "=== Building opencv_wasm_simd (SIMD128, no threads) ==="
mkdir -p "$SIMD_OUT/build" && cd "$SIMD_OUT/build"
emcmake cmake "$OPENCV_SRC" \
  "${COMMON_CMAKE_ARGS[@]}" \
  -DCMAKE_INSTALL_PREFIX="$SIMD_OUT" \
  -DCMAKE_CXX_FLAGS="-msimd128" \
  -DCMAKE_C_FLAGS="-msimd128" \
  -DWITH_PTHREADS_PF=OFF
cmake --build . -j"$(nproc)"
cmake --install .
echo "Done: $SIMD_OUT"

# --- Variant B: SIMD + pthreads ---
THREADS_OUT="$SCRIPT_DIR/../opencv_wasm_threads"
echo ""
echo "=== Building opencv_wasm_threads (SIMD128 + pthreads) ==="
mkdir -p "$THREADS_OUT/build" && cd "$THREADS_OUT/build"
emcmake cmake "$OPENCV_SRC" \
  "${COMMON_CMAKE_ARGS[@]}" \
  -DCMAKE_INSTALL_PREFIX="$THREADS_OUT" \
  -DCMAKE_CXX_FLAGS="-msimd128" \
  -DCMAKE_C_FLAGS="-msimd128" \
  -DWITH_PTHREADS_PF=ON
cmake --build . -j"$(nproc)"
cmake --install .
echo "Done: $THREADS_OUT"

echo ""
echo "=== All done! Next steps ==="
echo "  # Build omr_simd.js/wasm:"
echo "  emcmake cmake -S . -B build_omr_simd -DOPENCV_VARIANT=simd && cmake --build build_omr_simd"
echo ""
echo "  # Build omr_threads.js/wasm:"
echo "  emcmake cmake -S . -B build_omr_threads -DOPENCV_VARIANT=threads && cmake --build build_omr_threads"
