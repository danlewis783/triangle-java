# Slice 5c scope: making fine-feature refinement fast

Status: **scoped, not implemented.** Companion to
[refinement-small-features.md](refinement-small-features.md), which covered
*terminating* refinement on fine-featured boundaries (slices 5a/5b, shipped).
This doc covers making that case *fast*.

## 1. Where we are

The pure-Java mesher is now correct across the angle range, including the
captured q=33 fine-hole case (`rectangle-solid-with-hole.json`: 2×1 rectangle,
256-facet hole, region max area ≈ 0.00308). But that case is slow:

| | our mesher | native |
|---|---|---|
| q=33 captured case | 8,281 tris, **~58 s** | 2,745 tris, 105 ms |

Two independent gaps remain, both already noted in
[refinement-small-features.md](refinement-small-features.md):

- **Speed** — this doc. The refinement loop is O(N²).
- **Size** — ~3× native's triangle count (no free-vertex deletion; simpler
  off-centre/ordering). Out of scope here, but note that cutting N also cuts the
  O(N²) constant, so the two interact.

## 2. What the measurements taught me (do not re-learn this)

Performance work this session, in order:

1. **Filtered predicates, incremental refinement, off-centres** (shipped earlier)
   took the mesher from non-viable to fast on the standard range.
2. **Encroachment scan O(S·T) → O(S+T)** via an edge→apex index
   ([`306942a`](../src/main/java/com/acme/triangle/impl/JavaTriangleMesher.java),
   `encroachedSubsegment`): **74 s → 58 s.** Real, behaviour-preserving win.
3. **Removing the per-iteration `mesh.toOutput()` snapshot** (have the query
   helpers read `IncrementalCdt` live instead of copying the whole mesh each
   iteration): **no improvement, 58 s → 60 s. Reverted.**

The lesson from (3): **the snapshot is not the bottleneck.** It looked wasteful
(it copies all points/triangles and rebuilds adjacency every iteration), but
removing it changed nothing — so don't reach for it again expecting a win. The
actual costs are the per-iteration O(T) *algorithmic* work, all of which makes
the loop O(N²):

- **(a) `badTriangle` rescans every triangle every iteration**, calling
  `minAngleDeg` — three `Math.acos` per triangle. For the q=33 case that is on
  the order of **~190M `acos` calls**. This is the single biggest cost.
- **(b) `insertViaCavity` rebuilds global adjacency** — a `HashMap` over 3T
  edges — on **every** insertion.
- **(c) point location / incidence scans are O(T):** `locate` (interior
  insertion) and `incidentTriangles` (segment split) both linear-scan all
  triangles; `encroachedSubsegment` rebuilds its edge→apex map each iteration.

Net: every one of the ~N iterations does several O(T) passes ⇒ O(N²), dominated
by `acos` and `HashMap` churn.

## 3. The fix: maintained adjacency + work queues

This mirrors Triangle's `enforcequality` (triangle.c:8416), which is fast for
exactly this reason: it never rescans the whole mesh and never rebuilds
adjacency. Two coupled changes.

### 3.1 Maintained adjacency (stable triangle IDs)

Today `IncrementalCdt` stores `List<int[]> tris` where a triangle's identity is
its list position; every insertion rebuilds the list (reindexing) and adjacency
is recomputed on demand via a `HashMap`
([`IncrementalCdt.insertViaCavity`](../src/main/java/com/acme/triangle/impl/IncrementalCdt.java)).

Change to **stable triangle IDs with incremental neighbour links**:

- A triangle record holds its 3 corners, 3 neighbour IDs (or −1), its region
  attribute, and a liveness flag. Deleted triangles are marked dead and their
  slots freed for reuse (compact only when building the final output).
- `insertViaCavity` keeps its current cavity logic, but walks neighbours from the
  seed (O(cavity)) instead of building global adjacency (O(T)), and **relinks**
  locally: the new fan triangles get neighbour links to each other and to the
  cavity's outer ring, and the outer-ring triangles' links into the cavity are
  repointed to the new fan. Standard Bowyer–Watson with adjacency maintenance.
- This removes cost (b) entirely and makes each insertion O(cavity).

### 3.2 A maintained bad-triangle priority queue + dirty re-testing

Today `badTriangle` is an O(T) scan returning the first bad triangle (with the
MPW skip). Replace with Triangle's scheme (triangle.c:3711, `enqueuebadtriang`):

- A **priority queue keyed by shortest-edge length** — Triangle uses 4096
  length-bucketed FIFO queues, shortest edge = highest priority. (Note: by *edge
  length*, not angle. My earlier worst-first-by-**angle** experiment regressed
  area cases; do not repeat that — use edge length.)
- **Seed once** by testing all triangles (`tallyfaces`), applying the MPW skip
  from slice 5b.
- After each insertion/split, **re-test only the triangles that changed** (the
  cavity's new fan — `insertViaCavity` already knows them) and enqueue any newly
  bad ones. No global rescan ⇒ removes cost (a). This is Triangle's `triflaws`
  path.
- Likewise keep an **encroached-subsegment queue** (triangle.c:8081,
  `splitencsegs`) seeded once and fed by the dirty set, removing the
  per-iteration encroachment rebuild (cost c).
- Queue entries carry the triangle/subsegment reference, so dequeuing gives O(1)
  access — no `locate`/`incidentTriangles` scan. Re-validate on dequeue (the
  triangle may have been destroyed since it was enqueued; Triangle does this at
  triangle.c:8313).

### 3.3 The refinement loop becomes

```
build CDT once -> IncrementalCdt (maintained adjacency)
tally encroached subsegments; split them all (concentric shells)   // 5a
tally bad triangles into the length queue (MPW skip)               // 5b
while queue not empty and under the vertex cap:
    t = dequeue worst bad triangle; skip if stale
    p = offCentre(t)
    if p encroaches a subsegment: enqueue that subsegment; splitencsegs()
    else: insert p; re-test only the new triangles -> enqueue newly bad
```

This is the committed loop's logic, but driven by queues over a maintained mesh
instead of full rescans over a snapshot.

## 4. Sub-slices (each independently validatable)

1. **5c-1 — maintained adjacency.** Convert `IncrementalCdt` to stable IDs +
   incremental neighbour links; `insertViaCavity`/`splitSegment` relink locally.
   Keep the existing (rescan-based) refine loop on top. Validate: full suite +
   the q=33 timing should drop (removes cost b). This is the riskiest piece (mesh
   surgery) but self-contained — the oracle catches any topology error.
2. **5c-2 — bad-triangle length queue + dirty re-testing.** Replace
   `badTriangle`'s scan; re-test only the cavity's new triangles. Removes cost
   (a) — expected to be the largest drop.
3. **5c-3 — encroached-subsegment queue.** Removes cost (c) and the per-iteration
   snapshot falls out naturally (the loop no longer needs a full `toOutput` until
   the end).

Do them in order; each should show a measurable drop on the q=33 case and keep
384 tests green.

## 5. Validation

Same oracle as everything else — no new acceptance criterion.

- **Correctness:** full suite (384) stays green, especially `DifferentialTest`
  and the `refinesAFacetedHoleAtAHighAngleBound` (q=33) regression. Maintained
  adjacency must produce the same contract-valid meshes (the neighbour-slot and
  topology invariants in `MeshValidator` directly check the adjacency we'd now be
  maintaining by hand).
- **Speed target:** the q=33 captured case. Today 58 s; native is 105 ms. A
  correct 5c should bring it to seconds or below. Use the bench JSON mode
  (`gradlew bench --args="src/bench/resources/inputs/regression"`).
- Watch the synthetic area cases (`bench heavy`) for no regression — the maintained
  structure must not slow the common path.

## 6. Risks

- **Maintained adjacency is real mesh surgery** (relinking on cavity insertion and
  segment split). This is the genuinely hard part. Mitigation: the
  neighbour-slot + topology invariants in `MeshValidator` are a precise oracle for
  adjacency bugs; validate after every sub-slice, and consider an
  assertion/debug mode that cross-checks maintained adjacency against a fresh
  rebuild on small inputs.
- **Stale queue entries.** A triangle can be destroyed between enqueue and
  dequeue; must re-validate on dequeue (triangle.c:8313 does exactly this).
- **Don't reorder by angle.** Use shortest-edge length for the queue (see §3.2).

## 7. Effort

Larger than 5a/5b. 5c-1 (maintained adjacency) is the bulk and the risk; 5c-2/5c-3
are mechanical once the structure is in place. Recommend diagnose-first per
sub-slice, measuring the q=33 case after each, and stopping when it is fast
enough — full parity with native's 105 ms also needs the *size* fix (free-vertex
deletion), which is a separate effort.
