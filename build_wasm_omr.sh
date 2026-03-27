#!/bin/bash
set -e
cd /c/Kien/Mobile/orm/wasm-omr-mobile
source /c/Kien/Mobile/orm/emsdk/emsdk_env.sh || true
export PATH="/c/Kien/Mobile/orm/emsdk/upstream/emscripten:$PATH"
export EMSCRIPTEN="/c/Kien/Mobile/orm/emsdk/upstream/emscripten"

mkdir -p build
cd build

python "$EMSCRIPTEN/emcmake.py" cmake .. -DCMAKE_BUILD_TYPE=Release
cmake --build . -j8
