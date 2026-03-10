class CameraManager {
  constructor(videoEl) {
    this.videoEl = videoEl;
    this.stream = null;
    this.facingMode = "environment";
  }

  async start() {
    this.stop();

    this.stream = await navigator.mediaDevices.getUserMedia({
      video: {
        facingMode: { ideal: this.facingMode },
        width: { ideal: 1920 },
        height: { ideal: 1080 }
      },
      audio: false
    });

    this.videoEl.srcObject = this.stream;
    await this.videoEl.play();
  }

  async toggleFacing() {
    this.facingMode = this.facingMode === "environment" ? "user" : "environment";
    await this.start();
  }

  currentFacing() {
    return this.facingMode;
  }

  stop() {
    if (!this.stream) return;
    for (const track of this.stream.getTracks()) {
      track.stop();
    }
    this.stream = null;
  }
}

window.CameraManager = CameraManager;
