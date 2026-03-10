class UploadStore {
  constructor() {
    this.dbName = "omr-web-db";
    this.version = 4;
    this.stores = {
      projects: "projects",
      images: "images",
      projectImages: "project_images",
      scans: "scans",
      suspicious: "suspicious"
    };
  }

  async open() {
    return await new Promise((resolve, reject) => {
      const req = indexedDB.open(this.dbName, this.version);

      req.onupgradeneeded = () => {
        const db = req.result;

        if (!db.objectStoreNames.contains(this.stores.projects)) {
          const projects = db.createObjectStore(this.stores.projects, { keyPath: "id" });
          projects.createIndex("ts", "ts", { unique: false });
        }

        if (!db.objectStoreNames.contains(this.stores.images)) {
          db.createObjectStore(this.stores.images, { keyPath: "id" });
        }

        if (!db.objectStoreNames.contains(this.stores.projectImages)) {
          const pimg = db.createObjectStore(this.stores.projectImages, { keyPath: "id", autoIncrement: true });
          pimg.createIndex("projectId", "projectId", { unique: false });
          pimg.createIndex("projectKind", ["projectId", "kind"], { unique: false });
        }

        if (!db.objectStoreNames.contains(this.stores.scans)) {
          const scans = db.createObjectStore(this.stores.scans, { keyPath: "id", autoIncrement: true });
          scans.createIndex("projectId", "projectId", { unique: false });
          scans.createIndex("ts", "ts", { unique: false });
        }

        if (!db.objectStoreNames.contains(this.stores.suspicious)) {
          const suspicious = db.createObjectStore(this.stores.suspicious, { keyPath: "id", autoIncrement: true });
          suspicious.createIndex("projectId", "projectId", { unique: false });
          suspicious.createIndex("ts", "ts", { unique: false });
        }
      };

      req.onsuccess = () => resolve(req.result);
      req.onerror = () => reject(req.error);
    });
  }

  async createProject(name) {
    const project = {
      id: `project_${Date.now()}_${Math.floor(Math.random() * 9999)}`,
      name,
      ts: Date.now()
    };

    const db = await this.open();
    const tx = db.transaction(this.stores.projects, "readwrite");
    tx.objectStore(this.stores.projects).put(project);
    await this.waitTx(tx);
    db.close();
    return project;
  }

  async listProjects() {
    const db = await this.open();
    const tx = db.transaction(this.stores.projects, "readonly");
    const rows = await this.getAll(tx.objectStore(this.stores.projects));
    db.close();
    return rows.sort((a, b) => (b.ts || 0) - (a.ts || 0));
  }

  async getProject(projectId) {
    const db = await this.open();
    const tx = db.transaction(this.stores.projects, "readonly");
    const row = await this.getById(tx.objectStore(this.stores.projects), projectId);
    db.close();
    return row;
  }

  async updateProject(projectId, updates) {
    const db = await this.open();
    const tx = db.transaction(this.stores.projects, "readwrite");
    const store = tx.objectStore(this.stores.projects);
    const row = await this.getById(store, projectId);
    if (!row) {
      tx.abort();
      throw new Error("Project not found");
    }
    store.put({ ...row, ...updates });
    await this.waitTx(tx);
    db.close();
  }

  async saveImage(blob) {
    const db = await this.open();
    const tx = db.transaction(this.stores.images, "readwrite");
    tx.objectStore(this.stores.images).put({ id: "latest", blob, ts: Date.now() });
    await this.waitTx(tx);
    db.close();
  }

  async addProjectImage(projectId, kind, blob, name) {
    const db = await this.open();
    const tx = db.transaction(this.stores.projectImages, "readwrite");
    tx.objectStore(this.stores.projectImages).add({
      projectId,
      kind,
      blob,
      name,
      processed: false,
      ts: Date.now()
    });
    await this.waitTx(tx);
    db.close();
  }

  async listProjectImages(projectId, kind = null, onlyUnprocessed = false) {
    const db = await this.open();
    const tx = db.transaction(this.stores.projectImages, "readonly");
    const store = tx.objectStore(this.stores.projectImages);

    const rows = kind
      ? await this.getAllByIndex(store, "projectKind", [projectId, kind])
      : await this.getAllByIndex(store, "projectId", projectId);

    db.close();

    let out = rows.sort((a, b) => (a.ts || 0) - (b.ts || 0));
    if (onlyUnprocessed) out = out.filter((x) => !x.processed);
    return out;
  }

  async markProjectImageProcessed(id, processed = true) {
    const db = await this.open();
    const tx = db.transaction(this.stores.projectImages, "readwrite");
    const store = tx.objectStore(this.stores.projectImages);
    const row = await this.getById(store, id);
    if (row) {
      store.put({ ...row, processed });
    }
    await this.waitTx(tx);
    db.close();
  }

  async deleteProjectImage(id) {
    const db = await this.open();
    const tx = db.transaction(this.stores.projectImages, "readwrite");
    tx.objectStore(this.stores.projectImages).delete(id);
    await this.waitTx(tx);
    db.close();
  }

  async clearProjectImages(projectId, kind) {
    const rows = await this.listProjectImages(projectId, kind, false);
    if (rows.length === 0) return;

    const db = await this.open();
    const tx = db.transaction(this.stores.projectImages, "readwrite");
    const store = tx.objectStore(this.stores.projectImages);
    for (const row of rows) {
      store.delete(row.id);
    }
    await this.waitTx(tx);
    db.close();
  }

  async saveCommittedScan(projectId, record) {
    const db = await this.open();
    const tx = db.transaction(this.stores.scans, "readwrite");
    tx.objectStore(this.stores.scans).add({ ...record, projectId, ts: Date.now() });
    await this.waitTx(tx);
    db.close();
  }

  async saveSuspicious(projectId, record) {
    const db = await this.open();
    const tx = db.transaction(this.stores.suspicious, "readwrite");
    tx.objectStore(this.stores.suspicious).add({ ...record, projectId, ts: Date.now() });
    await this.waitTx(tx);
    db.close();
  }

  async listCommittedScans(projectId) {
    const db = await this.open();
    const tx = db.transaction(this.stores.scans, "readonly");
    const rows = await this.getAllByIndex(tx.objectStore(this.stores.scans), "projectId", projectId);
    db.close();
    return rows.sort((a, b) => (b.ts || 0) - (a.ts || 0));
  }

  async updateCommittedScan(id, updates) {
    const db = await this.open();
    const tx = db.transaction(this.stores.scans, "readwrite");
    const store = tx.objectStore(this.stores.scans);
    const row = await this.getById(store, id);

    if (!row) {
      tx.abort();
      throw new Error("Committed scan not found");
    }

    store.put({ ...row, ...updates });
    await this.waitTx(tx);
    db.close();
  }

  async deleteCommittedScan(id) {
    const db = await this.open();
    const tx = db.transaction(this.stores.scans, "readwrite");
    tx.objectStore(this.stores.scans).delete(id);
    await this.waitTx(tx);
    db.close();
  }

  async listSuspicious(projectId) {
    const db = await this.open();
    const tx = db.transaction(this.stores.suspicious, "readonly");
    const rows = await this.getAllByIndex(tx.objectStore(this.stores.suspicious), "projectId", projectId);
    db.close();
    return rows.sort((a, b) => (b.ts || 0) - (a.ts || 0));
  }

  async resolveSuspiciousToCommitted(suspiciousId, committedRecord) {
    const db = await this.open();
    const tx = db.transaction([this.stores.suspicious, this.stores.scans], "readwrite");

    const suspiciousStore = tx.objectStore(this.stores.suspicious);
    const scansStore = tx.objectStore(this.stores.scans);
    const row = await this.getById(suspiciousStore, suspiciousId);

    if (!row) {
      tx.abort();
      throw new Error("Suspicious not found");
    }

    scansStore.add({
      ...committedRecord,
      projectId: row.projectId,
      ts: Date.now(),
      sourceName: committedRecord.sourceName || row.sourceName,
      sourceType: committedRecord.sourceType || row.sourceType
    });

    suspiciousStore.delete(suspiciousId);
    await this.waitTx(tx);
    db.close();
  }

  async updateSuspicious(id, updates) {
    const db = await this.open();
    const tx = db.transaction(this.stores.suspicious, "readwrite");
    const store = tx.objectStore(this.stores.suspicious);
    const row = await this.getById(store, id);

    if (!row) {
      tx.abort();
      throw new Error("Suspicious not found");
    }

    store.put({ ...row, ...updates });
    await this.waitTx(tx);
    db.close();
  }

  async getProjectSummary(projectId) {
    const [committed, suspicious] = await Promise.all([
      this.listCommittedScans(projectId),
      this.listSuspicious(projectId)
    ]);

    const totalAnswered = committed.reduce((acc, row) => acc + (row.answeredCount || 0), 0);

    return {
      committedCount: committed.length,
      suspiciousCount: suspicious.length,
      totalAnswered
    };
  }

  async waitTx(tx) {
    await new Promise((resolve, reject) => {
      tx.oncomplete = () => resolve();
      tx.onerror = () => reject(tx.error);
      tx.onabort = () => reject(tx.error);
    });
  }

  async getAll(store) {
    return await new Promise((resolve, reject) => {
      const req = store.getAll();
      req.onsuccess = () => resolve(req.result || []);
      req.onerror = () => reject(req.error);
    });
  }

  async getAllByIndex(store, indexName, value) {
    return await new Promise((resolve, reject) => {
      const req = store.index(indexName).getAll(value);
      req.onsuccess = () => resolve(req.result || []);
      req.onerror = () => reject(req.error);
    });
  }

  async getById(store, id) {
    return await new Promise((resolve, reject) => {
      const req = store.get(id);
      req.onsuccess = () => resolve(req.result || null);
      req.onerror = () => reject(req.error);
    });
  }
}

window.UploadStore = UploadStore;
