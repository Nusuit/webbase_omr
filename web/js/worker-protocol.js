const OMR_PROTOCOL_VERSION = 2;

const OMR_MSG = {
  INIT: "omr/init",
  READY: "omr/ready",
  PROCESS_FRAME: "omr/process-frame",
  FRAME_RESULT: "omr/frame-result",
  PROCESS_SHEET: "omr/process-sheet",
  SHEET_RESULT: "omr/sheet-result",
  ERROR: "omr/error",
  GET_WASM_HEAP: "omr/get-wasm-heap",
  WASM_HEAP_RESULT: "omr/wasm-heap-result"
};

self.OMR_PROTOCOL_VERSION = OMR_PROTOCOL_VERSION;
self.OMR_MSG = OMR_MSG;
