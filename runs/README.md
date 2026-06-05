# Runs Directory

This directory stores experiment outputs used by the OMR paper. Treat it as an
artifact registry, not a scratch folder.

## Source-of-Truth Policy

- `batch_results/` contains per-sheet latency and prediction JSON files.
- `accuracy_eval/` contains ground-truth review and accuracy summaries derived
  from batch JSONs.
- `resources/` contains CPU/RAM/resource CSVs and generated resource plots.
- `_native_raw*` contains raw Android pulls kept for traceability.
- `train/` contains model-training artifacts and is not a paper-result table by
  itself.

When a new run is created, add a short entry to `RUN_LOG.md` with the date,
device, script/command, output paths, and whether the run supersedes an older
artifact. Do not silently replace paper-facing artifacts.

## Canonical vs Rerun Artifacts

The current 5-page manuscript still uses the canonical artifact set listed in
`docs/ARTIFACT_MANIFEST_5PAGE.md`.

Reruns are allowed and encouraged, but they are not automatically canonical.
If a rerun differs materially from the table currently in the paper, keep both
artifacts and document the difference before changing the manuscript.

## Legacy Folders

Files under a `legacy/` or `archived/` folder are retained for provenance and
comparison only. They should not be used for new paper tables unless the text
explicitly labels them as legacy/profile-derived evidence.
