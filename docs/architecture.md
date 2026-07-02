# Architecture

The map of the library: what the pieces are, which invariants hold them
together, and where to look when something misbehaves. Companion documents:
[refinement-performance.md](refinement-performance.md) (how the refinement
kernel got fast, and the C-parity audit) and
[refinement-small-features.md](refinement-small-features.md) (how it learned to
terminate on fine features).

## The public API

One interface, one factory, flat DTOs:

- `TriangleMesher.mesh(TriangleMesherInput) -> TriangleMesherOutput` — the
  whole consumer surface. The DTOs are Triangle-style flat parallel arrays;
  prefer `TriangleMesherInput.builder()`, which derives every count so the
  count/array mismatches the input contract rejects cannot be constructed.
- `TriangleMeshers` — the factory: `javaMesher()` (the pure-Java pipeline),
  `nativeMesher()` (the JNA adapter over Shewchuk's Triangle), and the
  decorators `validating(...)`, `differential(primary, reference, ...)`, and
  `capturing(...)`.
- `contract.InputValidator` / `contract.MeshValidator` — the two contracts
  (see below).
- `io.TriangleJson` — the JSON form of the input DTO; every capture and
  regression fixture is one of these documents.

Everything else lives in `impl` and may change freely. The pipeline is
flat-native front to back: the phases hand each other the internal flat stores
directly (`CdtResult` carries the vertex store, the compacted triangle arena,
and the recovered subsegments from construction into refinement, adopted
without copying), and the only conversions are reading the input DTO's arrays
at the front and writing the output DTO's arrays at the back.

## The pipeline (pure-Java mesher)

1. **Input contract** — `InputValidator` rejects a structurally broken DTO
   with named violations before any geometry runs (`MeshInputException`).
2. **PSLG normalization** (`ConstrainedDelaunayTriangulator.splitIntersections`)
   — crossing segments split at their intersection; vertices lying exactly on
   a segment subdivide it (T-junctions), one sorted pass per segment.
3. **Initial Delaunay** (`DelaunayTriangulator`) — incremental Bowyer-Watson
   over a super-triangle, insertion in Hilbert-curve order, remembering-walk
   point location.
4. **Constraint recovery + carving** (`ConstrainedDelaunayTriangulator.CdtMesh`)
   — Sloan channel flipping recovers each segment; Lawson flips restore the
   Delaunay property elsewhere; flood-fill from outside and from hole seeds
   carves the domain; region seeds flood attributes.
5. **Refinement** (`JavaTriangleMesher` driving `IncrementalCdt`) — Ruppert
   with Triangle's improvements: diametral-*lens* encroachment, off-centre
   Steiner points, concentric-shell segment splitting, the
   Miller–Pav–Walkington skip for input-imposed small angles, and a 4096-bin
   worst-first bad-triangle queue. Insertion is constrained Bowyer-Watson with
   maintained adjacency: O(cavity) per point, no global rework. A vertex-count
   cap turns a genuinely non-terminating bound into a typed failure.

Internal representations: vertices in `FlatPointList` (append-only interleaved
`double[]`), triangles in `FlatTriangleList` (slot arena carrying corners,
neighbour links, region attribute, and liveness; corners fixed at alloc,
neighbour links the one mutable facet), segments and all other dynamic
collections in fastutil primitive lists. Geometric truth comes from
`predicate.Predicates` — exact-sign orient2d/incircle behind a fast filter —
reached only through `impl.Geometry`. The one hand-rolled idiom is
generation-stamped scratch arrays (membership valid iff stamp equals the
current generation), which is an algorithm, not a container.

## The contracts (how correctness is defined)

- **Input contract** (`InputValidator`): counts agree with arrays, coordinates
  finite, no duplicate points, segment endpoints in range, no self-loops or
  duplicate segments, angle bound satisfiable.
- **Output contract** (`MeshValidator`): manifold topology with consistent
  orientation, neighbour-slot semantics, constrained-Delaunay empty-circle on
  non-segment edges, segments present as edge chains covering the input
  segments, holes empty, region attributes constant per region, quality bound
  met — except triangles wedged on a concentric shell across a small input
  angle (the MPW exemption both meshers rely on).

The output contract is deliberately the acceptance bar instead of
byte-equality with native: two correct meshers legitimately produce different
valid meshes. `DifferentialTriangleMesher` runs both and compares *contracts*,
not triangles. Inside the refinement mesh, `IncrementalCdt.adjacencyConsistent`
cross-checks the hand-maintained neighbour links against a from-scratch
rebuild (used by the structural tests).

## When something fails

- Every failure path — invalid input, contract violation, non-convergence,
  unexpected pipeline error — **dumps the input as JSON and names the file in
  the exception message** (`[input captured: ...]`). On by default; disable
  with `-Dtriangle.captureFailures=false`, direct with
  `-Dtriangle.captureDir=...`. That file is a ready-made regression fixture:
  drop it in `src/test/resources/regression/` and it is meshed and
  contract-validated by both meshers on every test run (see the README there
  for the case history).
- To capture *every* input, not just failures:
  `TriangleMeshers.capturing(mesher, "name")` with
  `-Dtriangle.captureCases=true`.
- Exploration harnesses: `./gradlew bench` (java-vs-native wall clock over
  synthetic suites or a directory of captured JSON), `./gradlew jmh`
  (statistically rigorous sweep), `./gradlew profile --args="<scenario> <s>"`
  (JFR recording of the pure-Java mesher; inspect with `jfr view hot-methods`).

## Performance posture

Fast *enough*, guarded rather than chased: the refinement kernel measures at
or below native Triangle, construction within ~2x, and mesh sizes total ~+1%
of native across the regression suite. The JMH sweep and the bench suites are
the regression guard; the profile task exists so the next performance question
starts with a measurement. The history — including the experiments that were
tried and rejected, and the sampler's safepoint-bias lessons — is in
[refinement-performance.md](refinement-performance.md).
