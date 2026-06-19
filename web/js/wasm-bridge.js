class WasmBridge {
  constructor() {
    this.module = null;
    this.ptr = 0;
    this.capacity = 0;
    this.resultPtr = 0;
    this.resultCapacityInts = 0;
    this.previewPtr = 0;
    this.previewCapacity = 0;
    this.warpedPtr = 0;
    this.warpedCapacity = 0;
  }

  async init() {
    if (this.module) return;
    // Cache-bust the .wasm fetch — Chrome caches `omr.wasm` aggressively and a
    // freshly rebuilt binary would otherwise be silently ignored.
    const _wasmBust = (typeof self !== "undefined" && self._wasmCacheBust) || ("v=" + Date.now());
    const moduleOptions = {
      // Worker runs under /js, but wasm output is in /wasm.
      locateFile: (file) => (file.endsWith(".wasm") ? `../wasm/${file}?${_wasmBust}` : file),
      print: (text) => {
        if (typeof self.onCppLog === "function") {
          self.onCppLog(text);
        } else {
          console.log(text);
        }
      }
    };
    if (typeof self._omrMainScriptUrlOrBlob === "string") {
      moduleOptions.mainScriptUrlOrBlob = self._omrMainScriptUrlOrBlob;
    }
    this.module = await OmrModule(moduleOptions);
  }

  ensureBuffer(bytes) {
    if (this.capacity >= bytes) return;
    if (this.ptr) {
      this.module._free(this.ptr);
      this.ptr = 0;
      this.capacity = 0;
    }
    this.ptr = this.module._malloc(bytes);
    this.capacity = bytes;
  }

  ensureResultBuffer(intCount) {
    if (this.resultCapacityInts >= intCount) return;
    if (this.resultPtr) {
      this.module._free(this.resultPtr);
      this.resultPtr = 0;
      this.resultCapacityInts = 0;
    }
    this.resultPtr = this.module._malloc(intCount * 4);
    this.resultCapacityInts = intCount;
  }

  ensurePreviewBuffer(bytes) {
    if (this.previewCapacity >= bytes) return;
    if (this.previewPtr) {
      this.module._free(this.previewPtr);
      this.previewPtr = 0;
      this.previewCapacity = 0;
    }
    this.previewPtr = this.module._malloc(bytes);
    this.previewCapacity = bytes;
  }

  ensureWarpedBuffer(bytes) {
    if (this.warpedCapacity >= bytes) return;
    if (this.warpedPtr) {
      this.module._free(this.warpedPtr);
      this.warpedPtr = 0;
      this.warpedCapacity = 0;
    }
    this.warpedPtr = this.module._malloc(bytes);
    this.warpedCapacity = bytes;
  }

  processRgbaFrame(rgba, width, height, roi) {
    const bytes = rgba.length;
    this.ensureBuffer(bytes);

    const heapView = this.module.HEAPU8.subarray(this.ptr, this.ptr + bytes);
    heapView.set(rgba);

    const blackCount = this.module._omr_process_frame(
      this.ptr,
      width,
      height,
      roi.x,
      roi.y,
      roi.w,
      roi.h
    );

    const out = new Uint8ClampedArray(bytes);
    out.set(this.module.HEAPU8.subarray(this.ptr, this.ptr + bytes));

    return {
      blackCount,
      result: out
    };
  }

  processSheet(rgba, width, height) {
    const bytes = rgba.length;
    const resultInts = 432;
    const previewWidth = 1700;
    const previewHeight = 2400;
    const previewBytes = previewWidth * previewHeight * 4;

    this.ensureBuffer(bytes);
    this.ensureResultBuffer(resultInts);
    this.ensurePreviewBuffer(previewBytes);
    this.ensureWarpedBuffer(previewBytes);

    const heapView = this.module.HEAPU8.subarray(this.ptr, this.ptr + bytes);
    heapView.set(rgba);

    const status = this.module._omr_process_sheet(this.ptr, width, height, this.resultPtr, resultInts);

    // WASM heap may have grown during processing (large vector allocations),
    // which detaches old TypedArray views. Always re-read from HEAPU8/HEAP32
    // using fresh subarray() calls after invoking into WASM.
    const out = new Uint8ClampedArray(bytes);
    out.set(this.module.HEAPU8.subarray(this.ptr, this.ptr + bytes));

    const raw = new Int32Array(resultInts);
    raw.set(this.module.HEAP32.subarray(this.resultPtr >> 2, (this.resultPtr >> 2) + resultInts));

    // Fetch the 1700x2400 normalized binary preview from C++
    let preview = null;
    const copied = this.module._omr_get_last_preview(this.previewPtr, previewBytes);
    if (copied > 0) {
      preview = new Uint8ClampedArray(previewBytes);
      preview.set(this.module.HEAPU8.subarray(this.previewPtr, this.previewPtr + previewBytes));
    }

    // Fetch the 1700x2400 COLOR warped image (before binarization) for overlay
    let warpedPreview = null;
    const warpedCopied = this.module._omr_get_last_warped(this.warpedPtr, previewBytes);
    if (warpedCopied > 0) {
      warpedPreview = new Uint8ClampedArray(previewBytes);
      warpedPreview.set(this.module.HEAPU8.subarray(this.warpedPtr, this.warpedPtr + previewBytes));
    }
    
    // Explicitly free the C++ static vector memory back to the WASM heap pool
    // to prevent uncontrolled memory growth fragmenting across N=100 sequences
    if (typeof this.module._omr_clear_previews === "function") {
      this.module._omr_clear_previews();
    }

    return {
      status,
      result: out,
      raw,
      preview,
      warpedPreview,
      previewWidth,
      previewHeight
    };
  }

  // YOLO corner-keypoint path. `pts` = [x0,y0,x1,y1,x2,y2,x3,y3] in source-image
  // pixels (any order); the C++ core sorts TL/TR/BR/BL and warps directly.
  processSheetWithCorners(rgba, width, height, pts) {
    if (typeof this.module._omr_process_sheet_with_corners !== "function" ||
        !pts || pts.length < 8) {
      return this.processSheet(rgba, width, height);
    }

    const bytes = rgba.length;
    const resultInts = 432;
    const previewWidth = 1700;
    const previewHeight = 2400;
    const previewBytes = previewWidth * previewHeight * 4;

    this.ensureBuffer(bytes);
    this.ensureResultBuffer(resultInts);
    this.ensurePreviewBuffer(previewBytes);
    this.ensureWarpedBuffer(previewBytes);

    this.module.HEAPU8.subarray(this.ptr, this.ptr + bytes).set(rgba);

    const status = this.module._omr_process_sheet_with_corners(
      this.ptr, width, height,
      pts[0], pts[1], pts[2], pts[3], pts[4], pts[5], pts[6], pts[7],
      this.resultPtr, resultInts
    );

    const out = new Uint8ClampedArray(bytes);
    out.set(this.module.HEAPU8.subarray(this.ptr, this.ptr + bytes));

    const raw = new Int32Array(resultInts);
    raw.set(this.module.HEAP32.subarray(this.resultPtr >> 2, (this.resultPtr >> 2) + resultInts));

    let preview = null;
    const copied = this.module._omr_get_last_preview(this.previewPtr, previewBytes);
    if (copied > 0) {
      preview = new Uint8ClampedArray(previewBytes);
      preview.set(this.module.HEAPU8.subarray(this.previewPtr, this.previewPtr + previewBytes));
    }

    let warpedPreview = null;
    const warpedCopied = this.module._omr_get_last_warped(this.warpedPtr, previewBytes);
    if (warpedCopied > 0) {
      warpedPreview = new Uint8ClampedArray(previewBytes);
      warpedPreview.set(this.module.HEAPU8.subarray(this.warpedPtr, this.warpedPtr + previewBytes));
    }

    if (typeof this.module._omr_clear_previews === "function") {
      this.module._omr_clear_previews();
    }

    return { status, result: out, raw, preview, warpedPreview, previewWidth, previewHeight };
  }

  processSheetWithHint(rgba, width, height, box) {
    if (typeof this.module._omr_process_sheet_with_hint !== "function") {
      return this.processSheet(rgba, width, height);
    }

    const bytes = rgba.length;
    const resultInts = 432;
    const previewWidth = 1700;
    const previewHeight = 2400;
    const previewBytes = previewWidth * previewHeight * 4;

    this.ensureBuffer(bytes);
    this.ensureResultBuffer(resultInts);
    this.ensurePreviewBuffer(previewBytes);
    this.ensureWarpedBuffer(previewBytes);

    const heapView = this.module.HEAPU8.subarray(this.ptr, this.ptr + bytes);
    heapView.set(rgba);

    const x1 = Math.max(0, Math.min(width - 1, Math.floor(box.x1)));
    const y1 = Math.max(0, Math.min(height - 1, Math.floor(box.y1)));
    const x2 = Math.max(0, Math.min(width - 1, Math.ceil(box.x2)));
    const y2 = Math.max(0, Math.min(height - 1, Math.ceil(box.y2)));
    const status = this.module._omr_process_sheet_with_hint(
      this.ptr,
      width,
      height,
      x1,
      y1,
      x2,
      y2,
      this.resultPtr,
      resultInts
    );

    const out = new Uint8ClampedArray(bytes);
    out.set(this.module.HEAPU8.subarray(this.ptr, this.ptr + bytes));

    const raw = new Int32Array(resultInts);
    raw.set(this.module.HEAP32.subarray(this.resultPtr >> 2, (this.resultPtr >> 2) + resultInts));

    let preview = null;
    const copied = this.module._omr_get_last_preview(this.previewPtr, previewBytes);
    if (copied > 0) {
      preview = new Uint8ClampedArray(previewBytes);
      preview.set(this.module.HEAPU8.subarray(this.previewPtr, this.previewPtr + previewBytes));
    }

    let warpedPreview = null;
    const warpedCopied = this.module._omr_get_last_warped(this.warpedPtr, previewBytes);
    if (warpedCopied > 0) {
      warpedPreview = new Uint8ClampedArray(previewBytes);
      warpedPreview.set(this.module.HEAPU8.subarray(this.warpedPtr, this.warpedPtr + previewBytes));
    }

    if (typeof this.module._omr_clear_previews === "function") {
      this.module._omr_clear_previews();
    }

    return {
      status,
      result: out,
      raw,
      preview,
      warpedPreview,
      previewWidth,
      previewHeight
    };
  }

  dispose() {
    if (this.ptr && this.module) {
      this.module._free(this.ptr);
    }
    if (this.resultPtr && this.module) {
      this.module._free(this.resultPtr);
    }
    if (this.previewPtr && this.module) {
      this.module._free(this.previewPtr);
    }
    if (this.warpedPtr && this.module) {
      this.module._free(this.warpedPtr);
    }
    this.ptr = 0;
    this.capacity = 0;
    this.resultPtr = 0;
    this.resultCapacityInts = 0;
    this.previewPtr = 0;
    this.previewCapacity = 0;
    this.warpedPtr = 0;
    this.warpedCapacity = 0;
  }
}

self.WasmBridge = WasmBridge;
