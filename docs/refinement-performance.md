# Slice 5c: making fine-feature refinement fast

Status: **constant-factor wins shipped; the structural change (5c-1) is the
focused next effort.** Companion to
[refinement-small-features.md](refinement-small-features.md) (slices 5a/5b —
*terminating* refinement on fine-featured boundaries, shipped). This doc covers
making that case *fast*.

## 1. Where we are

The benchmark is the captured q=33 fine-hole case
(`src/bench/resources/inputs/regression/rectangle-solid-with-hole.json`: 2×1
rectangle, 256-facet hole, region max area ≈ 0.00308). It is correct (a fully
`MeshValidator`-valid mesh) but slower than native; native does it in 2,745
triangles / 105 ms. Wall-clock progress this round, all committed, 384 tests
green throughout:

| step | time | commit |
|---|---|---|
| start (after slice 5 correctness) | 74 s | — |
| encroachment scan O(S·T)→O(S+T), edge→apex index | 58 s | `306942a` |
| drop trig: squared-cosine bad-triangle test (no `acos`) | **13 s** | `eda8702` |
| read mesh in place (no per-iteration snapshot) | **9.7 s** | `26ed658` |

**Cheap constant-factor wins are now exhausted.** Two gaps remain:

- **Speed** — this doc. The loop is still O(N²): each insertion rebuilds
  adjacency, and the encroachment index is rebuilt each iteration. Needs the
  structural change in §3.
- **Size** — we make ~3× native's triangle count (no free-vertex deletion;
  simpler off-centre/ordering). Separate effort, but cutting N also cuts the
  O(N²) constant.

## 2. What was already done, and the lesson

In order (see the commits above):

1. **Encroachment scan O(S·T) → O(S+T)** via an edge→apex index
   (`encroachedSubsegment`): 74 → 58 s.
2. **Squared-cosine bad-triangle test** (`belowAngleBound`, mirrors
   triangle.c:4036): replaced `minAngleDeg`'s three `Math.acos` per triangle
   with the squared cosine of the angle opposite the shortest edge vs
   `cos²(bound)`. **58 → 13 s — the biggest single win.** `acos` was the dominant
   cost (~190M calls).
3. **Read the mesh in place** (`IncrementalCdt` live views; build the output once
   at convergence instead of snapshotting every iteration): 13 → 9.7 s.

**The lesson (do not re-learn):** measure, don't guess. Step 3 was tried *before*
step 2 and showed **zero** gain — because `acos` (step 2) was masking it. Only
after the trig was gone did removing the snapshot become a real win. Conversely,
the snapshot *looked* like the obvious waste but wasn't the bottleneck until the
real one was removed first.

What's left is genuinely structural (per-insertion O(T) work); no further
constant-factor trick will move it.

## 3. The remaining fix: maintained adjacency + work queues

Mirrors Triangle's `enforcequality` (triangle.c:8416), which is fast precisely
because it never rescans the whole mesh and never rebuilds adjacency.

### 3.1 Maintained adjacency (stable triangle IDs) — slice 5c-1, the hard part

Today `IncrementalCdt` stores `List<int[]> tris` where a triangle's identity is
its list position; every insertion rebuilds the list (reindexing) and adjacency
is recomputed via a `HashMap`
([`IncrementalCdt.insertViaCavity`](../src/main/java/com/acme/triangle/impl/IncrementalCdt.java)).
This O(T)-per-insertion rebuild is the dominant remaining cost.

Change to **stable triangle IDs with incremental neighbour links**:

- A triangle record holds its 3 corners, 3 neighbour IDs (or −1), its region
  attribute, and a liveness flag. Deleted triangles are marked dead and their
  slots freed for reuse (compact only when building the final output).
- `insertViaCavity` keeps its current cavity logic, but walks neighbours from the
  seed (O(cavity)) instead of building global adjacency, and **relinks** locally:
  the new fan triangles link to each other and to the cavity's outer ring, and
  the outer-ring triangles' links into the cavity are repointed to the new fan.
  Standard Bowyer–Watson with adjacency maintenance. This makes insertion
  O(cavity).
- **Fan linking detail** (worked out, captured so it isn't re-derived): build new
  triangles as `{u, w, p}` with the inserted vertex `p` always at corner 2 — the
  boundary edge `(u,w)` is then always the edge opposite corner 2, so its
  neighbour is the outer-ring triangle. The two interior fan edges are `(p,u)`
  (opp corner 1) and `(w,p)` (opp corner 0). Pair adjacent fan triangles by
  cavity-boundary vertex: each boundary vertex `v` (≠ p) appears in exactly two
  new triangles — once as a `u` (its `(p,u)` edge, slot 1) and once as a `w` (its
  `(w,p)` edge, slot 0) — link those two.
- **Fiddly cases to get right:** the segment-split skip-edge (the split edge is
  not re-fanned, so its endpoints `a,b` appear in only one fan triangle each —
  their `(a,p)`/`(p,b)` edges are the new segment edges, neighboured across the
  segment) and the interior-segment two-sided spanning split (the cavity spans
  both sides; the new `(a,m)`/`(m,b)` edges are interior fan edges shared by
  triangles on opposite sides, with differing region attributes).

### 3.2 Bad-triangle priority queue + dirty re-testing — slice 5c-2

Today `badTriangle` rescans every triangle every iteration (now cheap per
triangle, but still O(T) per iteration). Replace with Triangle's scheme
(triangle.c:3711, `enqueuebadtriang`):

- A **priority queue keyed by shortest-edge length** — Triangle uses 4096
  length-bucketed FIFO queues, shortest edge = highest priority. **By edge
  *length*, not angle** — an earlier worst-first-by-angle experiment regressed
  area cases; do not repeat that.
- Seed once (`tallyfaces`), applying the MPW skip from slice 5b.
- After each insertion/split, **re-test only the triangles that changed** (the
  cavity's new fan, which `insertViaCavity` already knows) and enqueue any newly
  bad ones — Triangle's `triflaws` path. Removes the O(T) rescan.
- Queue entries carry the triangle reference, so no `locate`/`incidentTriangles`
  scan; **re-validate on dequeue** (the triangle may have been destroyed since —
  triangle.c:8313).

### 3.3 Encroached-subsegment queue — slice 5c-3

Keep an encroached-subsegment queue (triangle.c:8081, `splitencsegs`) seeded once
and fed by the dirty set, removing the per-iteration encroachment edge→apex
rebuild. (The per-iteration *snapshot* this slice would also have removed is
already gone — done in step 3 above.)

### 3.4 The refinement loop becomes

```
build CDT once -> IncrementalCdt (maintained adjacency)
tally encroached subsegments; split them all (concentric shells)   // 5a
tally bad triangles into the length queue (MPW skip)               // 5b
while queue not empty and under the vertex cap:
    t = dequeue worst bad triangle; skip if stale
    p = offCentre(t)
    if p encroaches a subsegment: enqueue that subsegment; split it
    else: insert p; re-test only the new triangles -> enqueue newly bad
```

Same logic as the committed loop, driven by queues over a maintained mesh instead
of full rescans.

## 4. Sub-slices and status

1. **5c-1 — maintained adjacency.** NOT started. The bulk and the risk (mesh
   surgery; see the fiddly cases in §3.1). Convert `IncrementalCdt` to stable IDs
   + incremental neighbour links; keep the existing rescan-based loop on top.
   Removes the per-insertion adjacency rebuild — expected the largest remaining
   drop. Self-contained; the oracle catches topology errors.
2. **5c-2 — bad-triangle length queue + dirty re-testing.** NOT started. Removes
   the per-iteration `badTriangle` rescan. Needs 5c-1 (stable IDs for queue
   entries).
3. **5c-3 — encroached-subsegment queue.** NOT started (the snapshot half is
   already done). Removes the per-iteration encroachment rebuild.

Do in order; measure the q=33 case after each; keep 384 tests green. Stop when
fast enough — native parity (105 ms) also needs the *size* fix (free-vertex
deletion), a separate effort.

## 5. Validation

Same oracle as everything else.

- **Correctness:** full suite (384) stays green, especially `DifferentialTest`
  and the `refinesAFacetedHoleAtAHighAngleBound` (q=33) regression. The
  neighbour-slot and topology invariants in `MeshValidator` directly check the
  adjacency 5c-1 maintains by hand — a precise oracle.
- **Speed target:** the q=33 captured case (currently 9.7 s; native 105 ms) via
  `gradlew bench --args="src/bench/resources/inputs/regression"`, or the timing
  harness pattern used this round (mesh it, print tris/ms/violations).
- Watch the synthetic area cases (`bench heavy`) for no regression.

## 6. Risks

- **Maintained adjacency is real mesh surgery** (relinking on cavity insertion and
  segment split — see §3.1's fiddly cases). The genuinely hard part. Mitigation:
  validate after every sub-slice; consider a debug assertion that cross-checks
  maintained adjacency against a fresh rebuild on small inputs.
- **Stale queue entries** — re-validate on dequeue (triangle.c:8313).
- **Don't reorder by angle** — use shortest-edge length for the queue.

## 7. Effort

5c-1 is the bulk and the risk; 5c-2/5c-3 are mechanical once stable IDs exist.
Diagnose-first per sub-slice, measure the q=33 case after each, stop when fast
enough.
