# Slice 5: terminating quality refinement on fine-featured boundaries

Status: **shipped** (concentric-shell splitting + the Miller–Pav–Walkington skip
rule, commit `57ad7c8`). The captured q=33 case now converges to a fully
`MeshValidator`-valid mesh where it previously diverged. This doc is kept as the
design record / rationale. For making that case *fast*, see
[refinement-performance.md](refinement-performance.md).

## 1. The problem

`JavaTriangleMesher` refines a constrained Delaunay mesh to a minimum-angle
bound (and per-region max area) by Ruppert/Üngör refinement
([JavaTriangleMesher.java](../src/main/java/com/acme/triangle/impl/JavaTriangleMesher.java)).
After slices 1–4 it is correct and fast for the **standard angle range**, but it
**fails to terminate** on a real captured input:
`src/bench/resources/inputs/regression/rectangle-solid-with-hole.json` — a 2×1
rectangle with a 256-facet polygonal hole, region max area ≈ 0.00308, and
`minAngleDegrees = 33`.

### Evidence (measured)

| Observation | Result |
|---|---|
| q=33 on a **clean** 4×1 rectangle (forces refinement, no fine features) | converges fine, q=20…34 |
| q=33 on the **captured fine-hole** case (our mesher) | diverges: climbs past 4,000 triangles, never converges |
| Same captured case, **native Triangle** | **2,745 triangles, and our `MeshValidator` reports 0 violations (0 quality)** |
| Cascade mechanism (instrumented) | all **interior insertions**; segments stabilize (~352), so it is *not* a subsegment-split cascade |
| Worst triangle at iteration 0 | the unrefined CDT already contains a **~0.09° sliver** along the faceted hole boundary |

**Conclusions:**
- The target is achievable — native produces a *fully* q=33-valid mesh of 2,745
  triangles. This is not an infeasible input.
- The divergence is **feature-related, not the angle bound**. Off-centres
  (slice 4) handle q=33 on clean geometry.
- We cascade because we lack Triangle's termination machinery for refinement
  near small/faceted features.

A quick experiment with worst-first ordering by **angle** (refine the smallest
min-angle triangle first) made the worst angle improve monotonically but still
did not converge, and regressed area-constrained cases ~2.6×. It was reverted.
Note Triangle orders by shortest **edge length**, not angle (see §2.4).

## 2. What Triangle does that we don't

Reference: `C:/dev/triangle/triangle.c`. Triangle's `enforcequality`
(triangle.c:8416) is:

1. `tallyencs` then `splitencsegs(0)` — split every encroached subsegment up
   front (conforming Delaunay), **using concentric shells** (§2.1).
2. `tallyfaces` — enqueue every bad triangle, **applying the skip rule** (§2.2).
3. Main loop (triangle.c:8460): dequeue the **worst** bad triangle (§2.4),
   `splittriangle` inserts its off-centre; **if that insertion encroaches a
   subsegment it is rejected** (`undovertex`, triangle.c:8367), the triangle is
   re-queued, and `splitencsegs(1)` splits the newly encroached subsegments
   (noting new bad triangles). Otherwise the triangle is done.

Four pieces matter for termination on fine features. We have none of them.

### 2.1 Concentric-shell subsegment splitting — triangle.c:8163–8186

When an encroached segment shares an endpoint with an adjacent segment
(`acuteorg`/`acutedest`), Triangle does **not** split at the midpoint. It splits
at a power-of-two distance from the shared endpoint:

```c
nearestpoweroftwo = 1.0;
while (segmentlength > 3.0 * nearestpoweroftwo) nearestpoweroftwo *= 2.0;
while (segmentlength < 1.5 * nearestpoweroftwo) nearestpoweroftwo *= 0.5;
split = nearestpoweroftwo / segmentlength;       /* fraction along the segment */
if (acutedest) split = 1.0 - split;              /* measure from the shared end */
```

This makes the new vertices around a shared vertex land on shared "concentric
circular shells" (equal radii), so the bridging triangles are isosceles instead
of ever-thinner slivers. Without it, splitting one segment encroaches its
neighbour, whose split encroaches the first, forever.

We currently always split at the midpoint
([IncrementalCdt.splitSegment](../src/main/java/com/acme/triangle/impl/IncrementalCdt.java)).

### 2.2 The Miller–Pav–Walkington skip rule — triangle.c:4084–4143

In `testtriangle`, a skinny triangle is **not enqueued** if its shortest edge's
two endpoints are both segment-interior vertices that lie on a common concentric
shell — i.e. equidistant from the vertex where their two segments meet:

```c
if (vertextype(base1)==SEGMENTVERTEX && vertextype(base2)==SEGMENTVERTEX) {
  /* ... find the segments containing base1 and base2, and their join vertex ... */
  if ((dist1 < 1.001 * dist2) && (dist1 > 0.999 * dist2)) return; /* don't split */
}
```

This is the rule that actually breaks the cascade: such a triangle's poor angle
is bounded by the input near that feature, so refining it is futile. Combined
with §2.1 (which puts the endpoints on equal shells), these triangles are
recognized and left alone. For the captured input the facets meet at ~178°, so
the triangles left unsplit still satisfy the bound (native: 0 quality
violations) — but the rule is what stops us from chasing them forever.

We have no equivalent: `badTriangle` enqueues every below-bound triangle.

### 2.3 Free-vertex deletion (Chew) — triangle.c:8122–8160

When splitting a *non*-shared encroached segment, Triangle first deletes any
free (Steiner) vertices already inside the segment's diametral circle. This
keeps the boundary clean and is part of why native produces *fewer* points.
Lower priority than §2.1/§2.2 but it needs a vertex-deletion op (see §3).

### 2.4 Shortest-edge priority queue — triangle.c:3711–3806

Bad triangles are processed worst-first via 4096 length-bucketed FIFO queues
keyed by **squared shortest-edge length**; shortest edges have highest priority.
This both improves termination/grading and removes per-iteration global rescans.

**Done (slice 5c-2).** The refine loop now drives a shortest-edge-length priority
queue and re-tests only the triangles each mutation changed, replacing the
per-iteration rescan; this was also the largest single speed win after maintained
adjacency. See [refinement-performance.md](refinement-performance.md) §3.2.

### Off-centre note

Triangle's off-centre (triangle.c:3603–3639, `offconstant` at triangle.c:1442)
is the same family as ours (perpendicular offset from the shortest-edge
midpoint, used only when nearer than the circumcentre). Our slice-4 placement is
adequate; no change needed here.

## 3. How this sits on the current code

The incremental machinery from slices 1–3
([IncrementalCdt](../src/main/java/com/acme/triangle/impl/IncrementalCdt.java))
is the right foundation. New capabilities it (or the refiner) will need:

- **Vertex provenance.** Track, per vertex, whether it is a SEGMENT vertex or a
  FREE (interior Steiner) vertex, and for segment vertices which original input
  segment they came from. Needed by §2.2 (both endpoints segment-interior, same
  join) and §2.3 (delete free vertices only).
- **Shell-aware `splitSegment`.** Take a split fraction (not always 0.5) and
  detect whether either endpoint is shared with another segment (§2.1).
- **Vertex deletion** on `IncrementalCdt` for §2.3 — a constrained
  cavity-retriangulation that removes a free vertex and re-fills its star. This
  is the most invasive new mesh op; defer if §2.1+§2.2 alone suffice.
- **A maintained bad-triangle queue + dirty set.** Replace the per-iteration
  snapshot/scan with: keep a length-priority queue; after each insertion/split,
  re-test only the triangles touched by that local update (the `IncrementalCdt`
  cavity already knows them). This is §2.4 and a standalone speedup.

## 4. Suggested sub-slices (diagnose-first, validate each)

1. **5a — concentric-shell `splitSegment`** (§2.1) + **vertex provenance**.
   Smallest change with the highest expected impact on the cascade. Re-run the
   q=33 captured case under the diagnostic harness; measure whether the interior
   cascade subsides.
2. **5b — MPW skip rule** (§2.2) in `badTriangle`. Expected to be the piece that
   actually terminates. After 5a+5b, the q=33 captured case should converge.
3. **5c — maintained adjacency + work queues** (§2.4). **Done** — performance, not
   correctness: maintained triangle adjacency, a shortest-edge-length bad-triangle
   queue, and a maintained encroachment index took q=33 from ~9.7 s to ~0.17 s.
   Full record in [refinement-performance.md](refinement-performance.md).
4. **5d — free-vertex deletion** (§2.3). **Open follow-up** — the remaining gap to
   native is *size* (we make a few percent more triangles on synthetic area cases).
   This is the lever that closes it, and the one genuinely hard new mesh op.

5a/5b made q=33 converge with a contract-valid mesh (the correctness goal); 5c
made it fast; 5d (size) is optional and not yet started.

## 5. Validation

Same oracle as every prior slice — no new acceptance criterion.

- **Must stay green:** full suite (currently 383), especially `DifferentialTest`
  and `JavaTriangleMesherTest` at q≤20. The skip rule (§2.2) must not drop below
  the bound on inputs where the bound *is* achievable — guard with the existing
  `MeshValidator` quality invariant.
- **New regression:** once it converges, add the captured q=33 case as a test —
  `javaMesher().mesh(in)` returns and `MeshValidator.validate(...)` is empty.
  (Native is the reference: 2,745 triangles, 0 violations.)
- **Differential:** extend the fuzz to a higher bound (q=30/33) on inputs with
  fine features so java-vs-native both stay contract-valid.
- **Bench:** the q=33 captured case should converge to a few thousand triangles
  in well under a second once the cascade is gone.

## 6. Risks

- **The MPW skip can mask a genuine failure.** If the bound is achievable but we
  wrongly skip, the mesh silently violates quality. The strict `MeshValidator`
  check on the regression + differential cases is the guard; keep it.
- **Vertex deletion (§2.3) is the one genuinely hard new mesh operation.** It is
  isolated to sub-slice 5d and is optional — prefer to avoid it.
- **Exact arithmetic at small features.** Triangle does one step of iterative
  refinement to keep split points collinear (triangle.c:8195–8210) and warns on
  precision exhaustion. Our split midpoints are plain `double`; shell splitting
  multiplies, not halves, the precision demands near tiny facets. Watch for it;
  the robust `Predicates` keep topology correct even if coordinates drift.

## 7. Effort

5a+5b (the convergence fix) is a focused change to `splitSegment`/`badTriangle`
plus vertex provenance — moderate, the bulk being careful porting of §2.1/§2.2
and test wiring. 5c is a separable performance/structure change. 5d is the only
large/uncertain piece and is optional. Recommend doing 5a+5b together,
diagnose-first, and stopping as soon as the captured q=33 case converges valid.
