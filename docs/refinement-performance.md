# Slice 5c: making fine-feature refinement fast

Status: **maintained adjacency (5c-1) and the bad-triangle length queue (5c-2)
shipped; the encroached-subsegment queue (5c-3) is the focused next effort.**
Companion to
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
| maintained adjacency: O(cavity) insertion, no per-insertion rebuild (5c-1) | **~2.3 s** | `3a62997` |
| bad-triangle length queue + dirty re-testing (5c-2) | **~0.5 s** | this round |

(Timings re-measured on this machine: the 9.7 s baseline clocked 8.2 s / 8,281
triangles here; 5c-1 took it to ~2.3 s / 5,801 triangles; 5c-2 to ~0.5 s / 2,644
triangles. The triangle count keeps *changing* because the refinement order
changes — 5c-1's slot reuse, then 5c-2's worst-first-by-shortest-edge queue
(Triangle's actual scheme) — each a different but `MeshValidator`-valid sequence.
5c-2's order is markedly better: 2,644 triangles is now *below* native's 2,745,
and the `bench heavy` area cases stayed within normal variation, no regression.)

**Cheap constant-factor wins are exhausted, the per-insertion adjacency rebuild
is gone (5c-1), and the per-iteration bad-triangle rescan is gone (5c-2).** Two
gaps remain:

- **Speed** — this doc. One per-iteration O(T) rescan remains: the encroachment
  edge→apex index is still rebuilt each iteration (`encroachedSubsegment`).
  Removing it is 5c-3 (§3.3).
- **Size** — we now make ~native's triangle count on the q=33 case; the synthetic
  area cases are still a touch above native. Free-vertex deletion is a separate
  effort, but cutting N also cuts the loop constant.

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

### 3.1 Maintained adjacency (stable triangle IDs) — slice 5c-1, **shipped**

Done. `IncrementalCdt` now stores stable triangle ids (slot indices) each
carrying their own 3 neighbour ids; deleted triangles are nulled and their slots
queued for reuse, and the live views carry holes that scanning consumers skip.
Insertion walks the cavity through the maintained links (O(cavity), with a
generation-stamped membership test so there is no full-size visited clear) and
relinks the new fan locally; `toOutput` compacts and emits the maintained
adjacency, so the `MeshValidator` neighbour-slot invariants are a direct oracle.
A debug `adjacencyConsistent()` cross-checks the hand-relinked links against a
from-scratch rebuild and is asserted in `IncrementalCdtTest`. The original design
notes are kept below as the record.

The previous shape stored `List<int[]> tris` where a triangle's identity was its
list position; every insertion rebuilt the list (reindexing) and recomputed
adjacency via a `HashMap` — the O(T)-per-insertion rebuild that was the dominant
remaining cost.

Stable triangle IDs with incremental neighbour links:

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

### 3.2 Bad-triangle priority queue + dirty re-testing — slice 5c-2, **shipped**

Done. The per-iteration `badTriangle` rescan is replaced by a
`PriorityQueue<BadTri>` in `JavaTriangleMesher` keyed by shortest-edge length
(squared, to avoid a `sqrt`), shortest = highest priority. Seeded once over the
whole mesh (applying the MPW skip); after each insertion/split only the new fan
(`IncrementalCdt.lastFanTriangles()`) is re-tested and the newly-bad enqueued.
Entries carry the slot id + corners at enqueue time, and `dequeueValidBad`
discards stale ones (slot freed, or reused for a different triangle — a surviving
triangle's corners are unchanged, so it is still bad and needs no re-test). When
a bad triangle's off-centre is rejected for encroachment, the triangle is
requeued (it was not refined). This kept the rescan-style "clear encroached
subsegments first" structure on top (still `encroachedSubsegment` each iteration
— that is 5c-3). The mesh order changes (worst-first), landing on a smaller mesh.

Original notes (Triangle's scheme, triangle.c:3711 `enqueuebadtriang`): a
priority queue keyed by shortest-edge length — Triangle uses 4096 length-bucketed
FIFO queues, shortest edge = highest priority. **By edge *length*, not angle** —
an earlier worst-first-by-angle experiment regressed area cases; do not repeat
that. Seed once (`tallyfaces`); after each insertion/split re-test only the
triangles that changed (Triangle's `triflaws` path); re-validate on dequeue
(triangle may have been destroyed — triangle.c:8313).

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

1. **5c-1 — maintained adjacency.** **DONE** (this round; 8.2 → ~2.3 s on the
   q=33 case, 384 tests green). Stable IDs + incremental neighbour links with the
   existing rescan-based loop kept on top; removed the per-insertion adjacency
   rebuild (insertion is now O(cavity)). The fiddly segment-split cases in §3.1
   are handled; the oracle (and `adjacencyConsistent()`) caught topology errors
   during development.
2. **5c-2 — bad-triangle length queue + dirty re-testing.** **DONE** (this round;
   ~2.3 → ~0.5 s on the q=33 case, 2,644 tris, 384 tests green). Removed the
   per-iteration `badTriangle` rescan; stable IDs (5c-1) make the queue entries
   safe to carry (revalidated on dequeue).
3. **5c-3 — encroached-subsegment queue.** NOT started (the snapshot half is
   already done). Removes the last per-iteration O(T) rescan: the encroachment
   edge→apex rebuild in `encroachedSubsegment`.

Do in order; measure the q=33 case after each; keep 384 tests green. Stop when
fast enough — native parity (105 ms) also needs the *size* fix (free-vertex
deletion), a separate effort.

## 5. Validation

Same oracle as everything else.

- **Correctness:** full suite (384) stays green, especially `DifferentialTest`
  and the `refinesAFacetedHoleAtAHighAngleBound` (q=33) regression. The
  neighbour-slot and topology invariants in `MeshValidator` directly check the
  adjacency 5c-1 maintains by hand — a precise oracle.
- **Speed target:** the q=33 captured case (now ~2.3 s after 5c-1; native 105 ms)
  via `gradlew bench --args="src/bench/resources/inputs/regression"`, or the
  timing harness pattern used this round (mesh it, print tris/ms/violations).
- Watch the synthetic area cases (`bench heavy`) for no regression.

## 6. Risks

- **Maintained adjacency is real mesh surgery** (relinking on cavity insertion and
  segment split — see §3.1's fiddly cases). The genuinely hard part — now done
  (5c-1). Mitigation in place: validate after every sub-slice, plus the debug
  `adjacencyConsistent()` cross-check of maintained links against a fresh rebuild,
  asserted in `IncrementalCdtTest`.
- **Stale queue entries** — re-validate on dequeue (triangle.c:8313).
- **Don't reorder by angle** — use shortest-edge length for the queue.

## 7. Effort

5c-1 is the bulk and the risk; 5c-2/5c-3 are mechanical once stable IDs exist.
Diagnose-first per sub-slice, measure the q=33 case after each, stop when fast
enough.
