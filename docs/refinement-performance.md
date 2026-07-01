# Refinement performance: from 74 s to 0.17 s

Status: **complete (slice 5c).** This is the record of how `JavaTriangleMesher`'s
quality refinement went from pathologically slow to ~1.6× native on the hard
case — the steps taken, the lessons, and what is left. Companion to
[refinement-small-features.md](refinement-small-features.md) (slices 5/5a/5b —
making that case *terminate*, the prerequisite; design record).

## 1. The benchmark and the result

The driver throughout is the captured q=33 fine-hole case
(`src/bench/resources/inputs/regression/rectangle-solid-with-hole.json`: a 2×1
rectangle with a 256-facet polygonal hole, region max area ≈ 0.00308,
`minAngleDegrees = 33`). It is the input that first *diverged* (slice 5 fixed
termination) and then merely *crawled*. Run it with:

```
gradlew bench --args="src/bench/resources/inputs/regression"
```

(`loadDirectory` is non-recursive — point at the directory holding the `.json`.)
The same document is also a frozen test fixture
(`src/test/resources/regression/`): `JavaTriangleMesherTest`'s
`refinesTheCapturedFineHoleRegressionAtQ33` meshes it and asserts a fully
contract-valid result on every `gradlew test` run.

Wall-clock, all committed, the full suite green throughout:

| step | time | tris | commit |
|---|---|---|---|
| start (after slice 5 correctness) | 74 s | — | — |
| encroachment scan O(S·T)→O(S+T), edge→apex index | 58 s | — | `306942a` |
| drop trig: squared-cosine bad-triangle test (no `acos`) | **13 s** | — | `eda8702` |
| read mesh in place (no per-iteration snapshot) | 9.7 s | 8,281 | `26ed658` |
| maintained adjacency, O(cavity) insertion (5c-1) | ~2.3 s | 5,801 | `3a62997` |
| bad-triangle length queue + dirty re-testing (5c-2) | ~0.5 s | 2,644 | `78b55b0` |
| maintained encroachment index, no edge→apex rebuild (5c-3) | **~0.17 s** | 2,644 | `5061976` |

Native Triangle does the same input in **2,745 triangles / ~105 ms**. We finish
in ~0.17 s / 2,644 triangles — ~1.6× native's time, and *below* its triangle
count. (Times re-measured on one machine; the 9.7 s row clocked 8.2 s there. The
constant-factor rows pre-date the triangle-count instrumentation.)

There is no longer a meaningful *speed* or *size* gap to close — see §5, which
records the size measurement and why free-vertex deletion was evaluated and
declined.

## 2. The path, step by step

### 2.1 Constant-factor wins (74 → 9.7 s)

Three cheap changes, in order — see [§4](#4-lessons-learned) for why the order
mattered:

1. **Encroachment scan O(S·T) → O(S+T).** `encroachedSubsegment` indexed every
   triangle edge to its (≤2) opposite apexes once, so the segment scan stopped
   being "for each of S segments, scan all T triangles." 74 → 58 s.
2. **Squared-cosine bad-triangle test.** `belowAngleBound` (mirrors
   triangle.c:4036) replaced `minAngleDeg`'s three `Math.acos` per triangle with
   the squared cosine of the angle opposite the shortest edge vs `cos²(bound)`.
   **58 → 13 s — the single biggest win.** `acos` was the dominant cost (~190M
   calls).
3. **Read the mesh in place.** `IncrementalCdt` exposes live views; the output is
   built once at convergence instead of snapshotting the whole mesh every
   iteration. 13 → 9.7 s.

After these the per-iteration work was genuinely *structural* — each insertion
rebuilt adjacency, and each iteration rescanned the mesh for bad triangles and
rebuilt the encroachment index. No constant-factor trick could move it.

### 2.2 The structural change: maintained adjacency + work queues (9.7 → 0.17 s)

This mirrors Triangle's `enforcequality` (triangle.c:8416), which is fast
precisely because it never rescans the whole mesh and never rebuilds adjacency.
It went in as three sub-slices, each measured, each keeping 384 tests green:

- **5c-1 — maintained adjacency.** Stable triangle ids carrying their own
  neighbour links; insertion walks the cavity through them (O(cavity)) and
  relinks the new fan locally, instead of rebuilding global adjacency from a
  `HashMap` every time. **9.7 → ~2.3 s.** (Implementation record: [§3.1](#31-maintained-adjacency-stable-ids--the-fan-linking-rule).)
- **5c-2 — bad-triangle length queue.** A priority queue keyed by shortest-edge
  length replaces the per-iteration rescan for the next bad triangle; only the
  triangles a mutation changed are re-tested. **~2.3 → ~0.5 s**, and the
  worst-first order also halved the mesh (5,801 → 2,644 tris). ([§3.2](#32-bad-triangle-length-queue--staleness-on-reused-slots).)
- **5c-3 — maintained encroachment index.** A per-segment incident-triangle index
  makes a subsegment's apexes an O(1) lookup, so `encroachedSubsegment` is an
  O(S) scan with no per-iteration edge→apex rebuild. **~0.5 → ~0.17 s**, and
  behaviour-preserving (same mesh). ([§3.3](#33-encroachment-via-a-maintained-apex-index).)

## 3. Implementation record (the parts worth not re-deriving)

### 3.1 Maintained adjacency, stable ids — the fan-linking rule

`IncrementalCdt` stores triangles in slots with a stable integer id. Each slot
holds 3 corners, 3 neighbour ids (or −1), its region attribute, and a liveness
flag (a `null` corner array). Deleted triangles are nulled and their slots queued
for reuse; the live views therefore carry holes, and every scanning consumer
skips `null`. The mesh is compacted only when `toOutput` builds the final result,
which emits the *maintained* adjacency — so `MeshValidator`'s neighbour-slot
invariants directly check the hand-relinked links (a precise oracle). A debug
`adjacencyConsistent()` additionally cross-checks them against a from-scratch
rebuild and is asserted in `IncrementalCdtTest`.

`insertViaCavity` gathers the cavity by walking neighbour links from the seed
(O(cavity)), with **generation-stamped** membership (a slot is in the current
cavity iff its stamp equals the current generation) so there is no full-size
visited-array clear. Then it re-fans, relinking locally:

- Build each new triangle as `{u, w, p}` with the inserted vertex `p` **always at
  corner 2**. The boundary edge `(u,w)` is then always opposite corner 2 (slot 2 →
  the outer-ring neighbour); the two interior fan edges are `(p,u)` (opp corner 1,
  slot 1) and `(w,p)` (opp corner 0, slot 0).
- **Pair adjacent fan triangles by shared boundary vertex:** each boundary vertex
  `v` (≠ p) appears in exactly two new triangles — once as a `u` (its slot-1 edge)
  and once as a `w` (its slot-0 edge) — link those two. The outer-ring neighbour's
  link back into the cavity is repointed to the new triangle.
- `ccw()` is gone on purpose: `p` **must** stay at corner 2 for the slot
  bookkeeping, and the star-shaped cavity guarantees `{u,w,p}` is already CCW.

**Fiddly cases that the design turns on:**

- *Segment-split skip-edge.* The split edge `(a,b)` is not re-fanned (it would
  form a degenerate `{a,b,p}`). Its endpoints then appear in only one fan triangle
  each, leaving their new `(a,m)`/`(m,b)` edges correctly neighboured across the
  segment.
- *Two-sided spanning split.* For an interior region-boundary segment the cavity
  spans both sides; the new `(a,m)`/`(m,b)` edges are interior fan edges shared by
  triangles on opposite sides, with differing region attributes — handled because
  each new triangle inherits its source cavity triangle's attribute.

### 3.2 Bad-triangle length queue — staleness on reused slots

A `PriorityQueue<BadTri>` keyed by **shortest-edge length** (squared, to avoid a
`sqrt`); shortest = highest priority. **By edge *length*, not angle** — an earlier
worst-angle-first experiment regressed area cases ~2.6× (see
[refinement-small-features.md](refinement-small-features.md) §1). It is seeded
once over the whole mesh (applying the Miller–Pav–Walkington skip from slice 5b);
after each insertion or split only the new fan
(`IncrementalCdt.lastFanTriangles()`) is re-tested and the newly-bad enqueued —
Triangle's `triflaws` path.

The subtlety is **staleness with slot reuse:** a queued slot may have been freed,
or reused for a *different* triangle. Each entry carries the slot id **and the
corners at enqueue time**; `dequeueValidBad` discards an entry whose slot is dead
or no longer holds those corners. A surviving triangle's corners are unchanged, so
it is necessarily still bad — no re-test needed on dequeue. One more case:  when a
bad triangle's off-centre is *rejected* (it would encroach a subsegment), the
triangle was not refined, so it is requeued (a no-op if the segment split happened
to destroy it).

### 3.3 Encroachment via a maintained apex index

The last per-iteration O(T) cost was that `encroachedSubsegment` rebuilt a
`HashMap` of all 3T triangle edges → apexes every iteration, just to read the
apexes of the S segments. Replaced with a maintained `segTri` map (segment edge →
one live incident triangle id), updated as the fan replaces incident triangles
(and, on a split, for the two new halves). `apexesOfSegment(a,b)` then returns the
≤2 opposite apexes in O(1): the indexed triangle gives one, a single adjacency hop
across `(a,b)` gives the other. `encroachedSubsegment` becomes an O(S) scan.

This was deliberately **not** a literal encroached-subsegment queue (triangle.c:8081,
`splitencsegs`). A queue fed only the dirty set would be O(dirty) rather than O(S)
per iteration, but it needs stale-entry revalidation and an edge→segment-index
map; the maintained index removes the same O(T) rebuild for far less machinery and
**zero behaviour change** — because it returns the same apexes the rebuild did, the
refinement order is identical, so the q=33 case and every `bench heavy` case keep
byte-identical triangle counts vs 5c-2. That invariance is itself a correctness
check. A real queue is a future option if S ever dominates (it does not here: S ≈
352).

## 4. Lessons learned

- **Measure, don't guess — and beware masking.** The `acos` removal (§2.1 step 2)
  was the biggest win and was *not* the change that "looked" wasteful. Reading the
  mesh in place (step 3) was tried *before* the trig removal and showed **zero**
  gain, because `acos` was masking it; only once the trig was gone did removing the
  snapshot become a real win. The obvious-looking waste (the snapshot) was not the
  bottleneck until the real one (trig) was removed first.
- **Order of bad-triangle processing changes the mesh — pick the right key.**
  Refinement output is order-dependent. Worst-first **by shortest edge length**
  (Triangle's actual scheme) both sped things up *and* shrank the mesh (5,801 →
  2,644). Worst-first **by angle** regressed area-constrained cases ~2.6× and was
  reverted — do not repeat it.
- **A behaviour-preserving optimization is the safest kind, and you can prove it.**
  5c-3 changes *how* encroachment is looked up, not the refinement order, so it
  must produce byte-identical meshes. Checking that the triangle counts were
  unchanged across six different inputs was a fast, decisive correctness signal —
  stronger and quicker than re-reasoning the geometry.
- **Make the existing oracle check the new invariant.** Having `toOutput` emit the
  *maintained* adjacency (rather than a safe from-scratch rebuild) means
  `MeshValidator`'s neighbour-slot checks exercise the hand-relinking on every
  test. The mesh surgery was the genuinely risky part; routing it through the
  oracle (plus the debug `adjacencyConsistent()` cross-check) caught topology
  errors immediately.
- **Stable ids unlocked the queues.** The bad-triangle and (potential)
  encroachment work queues are only safe to carry references in once a triangle
  has an identity that survives mutation — hence 5c-1 first, then 5c-2/5c-3. With
  reused slots, "is this entry still valid?" needs an enqueue-time corner snapshot
  to detect reuse.
- **Slice it, and keep every step green.** Each sub-slice was a separate commit
  measured on the q=33 case with the full suite green; the constant-factor wins
  likewise. That made each behaviour/perf change attributable and reversible.

## 5. Size: measured, and why free-vertex deletion was declined

The obvious "next gap" looked like *size*: native deletes free (Steiner) vertices
from an encroached segment's diametral circle before splitting it (Chew;
triangle.c:8122–8160, via `deletevertex` at :5626), and we never delete vertices,
so the assumption was that we make more triangles. **Measured, that assumption is
false.** Java vs native triangle counts (native q=33 = 2,745 matches the reference
exactly, so the comparison is trustworthy):

| case | java | native | Δ |
|---|---|---|---|
| `cdt_50 / 100 / 200` (no refinement) | = | = | 0 |
| `quality_10` | 51 | 48 | +3 (+6.3 %) |
| `quality_20` | 87 | 83 | +4 (+4.8 %) |
| `area_0.010_q20` | 154 | 150 | +4 (+2.7 %) |
| `area_0.0075_q20` | 207 | 221 | **−14 (−6.3 %)** |
| `area_0.005_q20` | 313 | 319 | −6 (−1.9 %) |
| `hole12_q20_a0.010` | 286 | 288 | −2 |
| **captured q=33** | **2,644** | 2,745 | **−101 (−3.7 %)** |

We are at parity or *below* native almost everywhere, and below it on the big real
case. The only consistent overage is a handful of triangles on tiny pure-*angle*
cases (`quality_10/20`: +3/+4), which comes from off-centre granularity — not
something free-vertex deletion even addresses, since that targets free vertices
near *segment splits*. Where deletion would most apply — heavy segment splitting,
i.e. the 256-facet q=33 case — we are already 101 triangles below native.

**Decision: free-vertex deletion is not worth building.** `deletevertex` is a
whole new mesh-surgery operation (remove an interior free vertex, re-triangulate
its star, maintain adjacency/segments) with real correctness risk, to shave ~4
triangles off small cases that aren't even the cases it targets. That is the
poor-ROI, high-risk move the "diagnose-first, stop when good enough" rule warns
against. Re-open only if a concrete input shows a size gap that this specifically
closes — measure first.

## 5a. Remaining low-priority ideas

None of these is motivated by a current measurement; they are the levers to reach
for *if a future input demands it*, roughly in order of likely payoff.

- **Remaining O(T) operations, now that the per-iteration ones are gone.**
  `locate` (point-in-domain for an interior insertion) and `incidentTriangles`
  (segment-split seeds) are still linear scans, and `splitSegment` uses the latter.
  They are not in the per-iteration hot path the way the old rescans were, but on
  much larger meshes they resurface — **measured:** the captured
  `rect-3x4-poly-30gon-inner` case (a 3×4 rectangle around an inscribed 30-gon at
  q=33) meshes to ~36.7k triangles (≈ 18k vertices, *below* native's 41.2k) but
  takes ~25 s, almost all of it in these O(T) scans (each of ~18k insertions does
  an O(T) `locate`). This is the case that motivated raising the convergence cap
  (`MIN_VERTEX_CAP` 10k → 100k) so a feasible-but-large mesh is not rejected; see
  the cap note in `JavaTriangleMesher`. `locate` could be seeded from the bad
  triangle whose off-centre is being inserted (it is adjacent); `incidentTriangles`
  could use the `segTri` index (segment edge → one incident triangle, plus an
  adjacency hop for the other) and drop the scan entirely. This is now the largest
  remaining speed lever, but only on these few very heavy inputs.
- **A real encroached-subsegment queue.** If an input has S large enough that the
  O(S)-per-iteration scan dominates, replace it with the dirty-set-fed queue
  described in §3.3. Low priority until a benchmark shows S mattering.
- **Off-centre placement / grading.** The slice-4 off-centre is adequate; Triangle's
  is the same family. If a future input grades poorly, revisit the target-angle
  margin and the circumcentre clamp — but only with a measured case in hand (per
  the first lesson).
- **Allocation churn.** Insertion allocates several small `int[]` per fan triangle
  and short-lived maps per insertion; `apexesOfSegment` returns a fresh `int[2]`
  per segment per iteration. Negligible on these inputs, but the obvious target if
  GC ever shows up in a profile.

## 6. How it is validated and measured

- **Correctness:** the full suite stays green, especially `DifferentialTest`
  (java-vs-native fuzz, both must honour the contract), the synthetic
  `refinesAFacetedHoleAtAHighAngleBound` (q=33) test, and
  `refinesTheCapturedFineHoleRegressionAtQ33`, which meshes the exact captured
  input above and asserts a contract-valid result (plus a generous triangle-count
  ceiling as a size-regression guard). `MeshValidator`'s neighbour-slot and
  topology invariants check the maintained adjacency by hand;
  `adjacencyConsistent()` cross-checks it against a rebuild on the scenario inputs.
- **Speed/size:** `gradlew bench --args="src/bench/resources/inputs/regression"`
  for the q=33 captured case and `gradlew bench --args="heavy"` for the synthetic
  area + quality cases. Each row prints `tri` (java) next to `nat_tri` (native)
  alongside `java_ms` / `native_ms` / `ratio`, so both the speed and the size
  comparison are built in — watch the area cases for no size regression after any
  ordering change. The §5 size table was taken straight from these `tri` vs
  `nat_tri` columns.

## 7. The C-parity audit and the flat 3× (2026-07)

A full pass comparing this implementation against `triangle.c`, motivated by the
JMH sweep showing a *uniform* ~3× over native at every size — a flat ratio means
matching asymptotics (the §2 structural work held up), so the gap had to be
either constant factors or a technique C has that we lack. Both turned out true.
Everything below is committed, each slice measured, the suite green throughout.

### 7.1 What the audit found present (no action)

Worst-first bad-triangle queue by shortest edge, maintained adjacency +
O(cavity) insertion, incremental encroachment work queue, off-centres,
concentric shells, Miller–Pav–Walkington skip, squared-cosine angle test,
segment recovery by local flipping, and refinement point-location seeded from
the bad triangle — all present on both sides. (The §5a `locate`/
`incidentTriangles` scans had already been fixed by the time of the audit:
insertion seeds from the bad triangle; `incidentTriangles` reads the `segTri`
index. `rect-3x4-poly-30gon-inner`, the §5a ~25 s case, is now ~66 ms.)

Deliberately still different: no free-vertex deletion (§5, declined on
measurement); C's 4096-bucket bad-triangle queue vs our exact `PriorityQueue`
(the heap shows at ~10% on ring-like cases — port the buckets only if a
measurement demands it); C's divide-and-conquer initial Delaunay vs our
incremental Hilbert-order Bowyer–Watson (same asymptotics, C has smaller
constants; not worth the rewrite).

### 7.2 Constant factors (profile-driven, not C techniques)

JMH stack profiles (`-prof stack`) pinned the flat 3×:

- **`Math.hypot` in `offCentre`** — two calls per off-centre; replaced with
  `sqrt`. The same lesson as `acos` in §2.1: JDK math intrinsics are not equal.
  (Beware the sampler's safepoint bias: it *reported* hypot at 90%; the real
  win was ~25%.)
- **Boxed collections on the hot paths** — `HashMap<Long, …>` edge lookups in
  the cavity gather, the `Topology.neighbors` build, per-insertion
  `HashMap<Integer, Integer>` fan linking, `Integer` deques. Replaced with a
  primitive open-addressed `LongIntMap`, generation-stamped int-array fan
  scratch, and int-array stacks. Construction 3.3×→2.2×, refinement kernel
  2.5×→1.3×.
- **Per-call expansion buffers in the adaptive incircle** — a per-thread
  scratch (C keeps these on the stack) took the cocircular-ring cases another
  ~35% down.

### 7.3 C techniques that were genuinely missing (ported)

- **Adaptive predicate stages** (`counterclockwiseadapt`/`incircleadapt`).
  The A-filter previously fell straight to `BigDecimal`. Now: orient2d runs
  B/C/D in expansions (D exact); incircle runs B/C and finishes, when B/C
  cannot separate (only near-exactly-cocircular inputs), with an exact
  expansion evaluation of the same determinant. No arbitrary precision remains.
  Validated by the predicates oracle plus a 2M-case differential fuzz against
  an independent BigDecimal reference (zero disagreements) — signs are
  identical by construction, so mesh outputs were byte-identical, a built-in
  correctness check (§4).
- **Diametral-lens encroachment** (triangle.c:3925). C's default encroachment
  region is the lens (apex angle ≥ 180° − 2·minangle), not the diametral
  circle; the circle split ~50% more subsegments than native on thin-feature
  inputs (`circles-r0p51-r0p5`: 15,165 vs native's 10,065 triangles → 10,681
  after). Because the lens no longer implies "a candidate beyond a boundary
  subsegment is rejected", the cavity gather adds C's blocked-walk rejection
  (VIOLATINGVERTEX, triangle.c:8375) explicitly: a candidate on or beyond a
  boundary subsegment returns it for splitting regardless of the lens. Heavy
  suite after: quality/area triangle counts equal to or below native across
  the board.
- **T-junction handling that is not quadratic.** Not a refinement item, but
  the audit's new `cdt-grid-50k` JMH scenario (integer lattice) exposed
  `splitIntersections` re-scanning all segments × all vertices after every
  single split: 102 s where native takes 38 ms. One sorted pass per segment
  (subdivision creates no new candidates) plus bounding-box prefilters on both
  the crossing and on-segment scans: 180 ms.

### 7.4 Where it stands

JMH (Java 8, single fork), after all of the above: construction ~2.2×
native, refinement kernel ~1.3–1.5×, captured q=33 hole case ~1.8×, cocircular
256-gon ~4×, thin annulus ~3.3×. The remaining premium on the cocircular
family is adaptive-incircle inner-loop cost (expansion arithmetic per cavity
test — C pays it too, with smaller constants) plus the `PriorityQueue`; those
are the next levers, measurement first. The JMH sweep now covers the regimes
that used to be invisible: `cdt-grid-50k` (degenerate predicates in
construction) and `ref-hole/circle/rings-q33` (captured refinement cases).
