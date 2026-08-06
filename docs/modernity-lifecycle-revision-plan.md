# Modernity Revision-Aware Lifecycle Plan

Status: proposed

Implementation repository: `~/Developer/modernity`

## Problem

Modernity currently treats lifecycle validation as a fixed procedure.
`sync_build_boot_capture` stops the server, refreshes the sandbox, performs a
full compile, boots the server, and captures evidence on every invocation. It
cannot determine whether an earlier compile, test, boot, or capture is still
valid for the current source.

`SandboxState` only describes execution state such as `Booting` and `Ready`. It
does not identify which source revision is synchronized, compiled, tested,
running, captured, or installed. Visual capture has its own artifact hashes,
but that provenance is not shared with the rest of the lifecycle.

This causes three types of churn:

- unchanged stages repeat because their validity is unknown;
- a late failure causes successful earlier stages to repeat;
- refresh requires stopping the server, so candidate revisions cannot be built
  while the previous revision remains active.

## Goals

- Reuse every stage whose inputs and outputs remain valid.
- Keep the active server running during ordinary edit and compile loops.
- Promote a candidate only when runtime verification is requested.
- Persist successful stages so retries and daemon restarts reuse them.
- Preserve low-level tools for diagnosis and manual control.
- Explain why each stage was performed, skipped, or invalidated.

## Non-Goals

- Hot-reloading arbitrary Java classes into Minecraft.
- Treating timestamps alone as content identity.
- Replacing Gradle dependency and task caching.
- Using session-authored composite tools to define lifecycle correctness.

## Invariants

1. The durable source project remains the source of truth.
2. A stage is reusable only when its input key matches and its outputs pass
   independent verification.
3. A running server is identified by process identity and artifact revision;
   `Ready` alone is insufficient.
4. Each successful stage is persisted before the next stage begins.
5. Candidate failure does not stop or invalidate a healthy active runtime.
6. A resource overlay cannot make an old Java artifact appear current.
7. Lifecycle mutations for one sandbox are serialized.

## Revision Model

### Incremental project manifest

Add `services/sandbox/core/revisions.py` and persist
`revision-manifest.json` in each sandbox workspace. Each file entry records:

- relative path;
- file size and mode;
- `mtime_ns` and `ctime_ns`;
- SHA-256 content digest.

Every inspection walks directory entries, but it reads and hashes contents only
when the metadata tuple changes. The initial inspection hashes the complete
project. Existing `IGNORED_COPY_PATTERNS` continue to exclude generated, VCS,
IDE, and runtime directories.

Produce deterministic component revisions for:

- `build`: Gradle wrapper, scripts, properties, catalogs, and `libs`;
- `main_code`: production Java and other compiled sources;
- `resources`: production resources and durable generated assets;
- `tests`: GameTest and other test sources and resources;
- `source`: a root hash covering all synchronized inputs.

Derive each revision from sorted paths, modes, and content hashes. UUIDs may
remain trace identities, but must not be validity keys. Reuse this scanner in
place of both `controller._project_snapshot` and the full-tree hashing in
`visual.artifacts.source_file_hashes`.

### Lifecycle ledger

Add `services/sandbox/core/lifecycle_ledger.py` and atomically persist
`lifecycle-revisions.json`.

| Stage | Input key | Verified output |
| --- | --- | --- |
| `synced` | source revision + destination slot | matching sandbox manifest |
| `quick_compiled` | build + code + resources + profile | Gradle task success |
| `assembled` | buildable revision + profile | JAR path, size, SHA-256 |
| `tested` | artifact + tests + selector/config | passing test result |
| `running` | artifact + boot configuration | process, endpoints, boot ID |
| `captured` | runtime + request + scenario + resources | evidence and hashes |
| `installed` | JAR hash + resolved client directory | installed file and hash |

Each record contains its stage, input key, output evidence, completion time,
duration, and trace identity. Do not eagerly delete downstream records. The
planner rejects records whose keys no longer match, preserving diagnostics and
making invalidation deterministic.

### Execution state versus revision state

Keep `SandboxState` for process execution and failure reporting. Revision
validity belongs in the ledger. Extend `SandboxStatus` with a compact summary:

```json
{
  "source_revision": "...",
  "candidate_revision": "...",
  "active_revision": "...",
  "assembled_revision": "...",
  "tested_revision": "..."
}
```

## Targets and Policies

Add explicit targets: `synced`, `quick_compiled`, `assembled`, `tested`,
`running`, `captured`, and `installed`.

Policies supply defaults without hiding the requested target:

- `iteration`: synchronize and perform a quick compile;
- `checkpoint`: assemble, run selected tests, and optionally promote/capture;
- `release`: assemble, run full configured verification, and install.

The caller requests an outcome rather than a procedure. The planner calculates
the minimum required transitions. An optional `force` argument may name
specific stages; there should be no default global rebuild flag.

## Candidate and Active Slots

The current single `workspace/project` cannot safely represent both a running
revision and a new candidate. Introduce two generation slots:

```text
workspace/
  slots/0/project/
  slots/1/project/
  lifecycle-revisions.json
  revision-manifest.json
```

The ledger identifies the active and candidate slots. The active slot remains
unchanged while its server runs. Source edits are synchronized and built in the
candidate slot. Promotion performs this transaction:

1. Verify the candidate artifact and required tests.
2. Stop the active runtime.
3. Mark the candidate slot active.
4. Boot and verify that exact artifact revision.
5. Retain the old slot as the next candidate.

If boot fails, record the failure without claiming that the candidate is
active. Rollback to the previous artifact is a separate recovery policy.

For compatibility, treat an existing `workspace/project` as slot zero until an
atomic, retryable migration completes.

## Controller and Tool API

Add these controller operations:

```python
lifecycle_status() -> LifecycleStatus
plan_lifecycle(target, policy, request=None, force=()) -> LifecyclePlan
ensure_lifecycle(target, policy, request=None, force=()) -> LifecycleResult
```

`LifecyclePlan` lists ordered actions and reusable stages with a reason for each
decision. `LifecycleResult` reports performed work, reused work, final revision
state, and failure evidence. Full Gradle and server logs remain opt-in.

Expose matching operations through:

- `services/sandbox/daemon.py`;
- `services/sandbox/client.py`;
- new `lifecycle_status`, `lifecycle_plan`, and `ensure_lifecycle` tools;
- `services/tooling/tools/__init__.py` registration.

Retain `compile`, `gametest`, `boot`, `stop`, and refresh tools. Direct mutating
calls must update or invalidate the same ledger so manual actions cannot create
false provenance.

Convert `sync_build_boot_capture` into a compatibility wrapper around
`ensure_lifecycle(target="captured", policy="checkpoint")`. Replace visual
errors that prescribe a fixed composite tool with a required target and an
invalidation reason.

Installation provenance belongs in the client operation because the resolved
Minecraft directory is part of its validity key.

## Concurrency and Persistence

- Add a daemon-owned lock per sandbox for planning and mutation.
- Recheck source revision after acquiring the lock and before committing.
- Write manifests and ledgers using temporary files, `fsync`, and atomic rename.
- Never hold a global daemon lock during Gradle, boot, tests, or capture.
- Write stage records only after output verification succeeds.

## Telemetry

Add lifecycle planning and stage completion events containing:

- target, policy, input key, and output key;
- performed/reused decision and reason;
- duration and failure type;
- files scanned, files rehashed, and bytes rehashed;
- active and candidate slot changes.

This measures reuse without collecting file contents. Existing compile and boot
events remain for compatibility.

## Delivery Plan

### Phase 1: Revision foundation

- Implement incremental manifests and component hashes.
- Refactor sandbox diff and visual provenance to use them.
- Recover from an invalid manifest by rebuilding it.

Acceptance criteria:

- unchanged inspections perform no content hashing;
- current diff and visual stale behavior remains correct;
- hashes are deterministic across daemon restarts.

### Phase 2: Ledger and read-only planner

- Implement ledger models, atomic persistence, and output verification.
- Record current refresh, compile, GameTest, boot, capture, and install actions.
- Add `lifecycle_status` and `lifecycle_plan` without changing execution.
- Trace planner decisions in shadow mode against existing workflows.

Acceptance criteria:

- every operation is explained as required or reusable;
- daemon restart preserves decisions;
- missing or tampered outputs are never reused.

### Phase 3: Lazy executor

- Implement `ensure_lifecycle` using the current single project slot.
- Persist after every successful stage.
- Delegate the existing composite tool to the executor.
- Preserve low-level tools as an escape hatch.

Acceptance criteria:

- unchanged capture performs no refresh, compile, or boot;
- boot failure retry reuses assembly and tests;
- capture failure retry reuses the running revision.

### Phase 4: Candidate and active generations

- Add slot migration and slot-aware sessions and backends.
- Build candidates while the active runtime remains untouched.
- Implement verified promotion and slot recycling.
- Share Gradle caches where safe while keeping build outputs slot-local.

Acceptance criteria:

- iteration edits do not stop the active server;
- only a runtime target causes at most one required restart;
- candidate failures leave the active runtime healthy.

### Phase 5: Visual and installation integration

- Reuse the persistent visual client by artifact and settings hashes.
- Preserve the resource-overlay path for resource-only changes.
- Record capture request/scenario hashes and installed file hashes.
- Replace fixed stale-artifact recovery instructions.

Acceptance criteria:

- resource-only edits require no server or client restart;
- identical captures reuse all valid prerequisites;
- installation is a no-op when the expected JAR is installed.

### Phase 6: Rollout

- Compare shadow planner decisions with actual operations.
- Enable lazy execution behind a configuration flag.
- Make it default after integration and interruption tests pass.
- Retain force-stage diagnostics and low-level tools.

## Test Matrix

Cover:

- initial and unchanged incremental scans;
- additions, removals, permission changes, same-size edits, and renames;
- build, code, resource, and test invalidation;
- no-op synchronization and selective copying;
- successful-stage reuse after a later failure;
- daemon restart and corrupt or truncated ledger recovery;
- missing or modified JAR and installed-file detection;
- resource-only overlay reuse;
- candidate compilation while the active revision remains running;
- promotion success, boot failure, and retry;
- concurrent requests for the same and different sandboxes;
- direct low-level operations updating provenance;
- existing workspace and interrupted migration recovery.

## Success Criteria

For revision A running and successive source edits B, C, and D:

```text
A: remains running
B: synchronize candidate + quick compile
C: synchronize candidate + quick compile
D checkpoint: assemble + test + one promotion/restart + capture
```

Repeating the D request does not repeat valid compile, test, boot, capture
prerequisites, or installation merely because a new tool call or agent turn
began.
