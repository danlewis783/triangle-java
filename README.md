# triangle-java

A pure-Java reimplementation of the subset of Jonathan Shewchuk's
[Triangle](https://www.cs.cmu.edu/~quake/triangle.html) mesh generator that our
consumer uses — constrained quality Delaunay triangulation of a planar
straight-line graph (PSLG) with holes and regions — behind a small facade, with
the native C library available as an interchangeable fallback.

The goal is **contract equivalence, not byte-for-byte reproduction**: the
consumer needs *a* valid mesh with the right structure, so this produces a valid
constrained-Delaunay quality mesh that need not match Triangle's exact vertex
numbering, triangle order, or Steiner-point placement.

Targets **Java 8**. Build with Gradle (`gradlew`).

---

## Quick start

```java
import com.acme.triangle.*;

TriangleMesherInput in = new TriangleMesherInput();
in.pointList   = new double[]{0,0, 1,0, 1,1, 0,1};   // x0,y0,x1,y1,...
in.numberOfPoints = 4;
in.segmentList = new int[]{0,1, 1,2, 2,3, 3,0};      // boundary
in.segmentMarkerList = new int[]{1,1,1,1};
in.numberOfSegments = 4;
in.minAngleDegrees = 20;                             // quality bound (0 = off)
in.quiet = true;

TriangleMesher mesher = TriangleMeshers.javaMesher();   // or .nativeMesher()
TriangleMesherOutput out = mesher.mesh(in);
```

```
./gradlew test      # 360+ tests: predicates, contract, both meshers, fuzz
```

---

## The facade

The consumer depends only on the `com.acme.triangle` package:

- **`TriangleMesher`** — the single entry point: `mesh(input) -> output`.
- **`TriangleMesherInput` / `TriangleMesherOutput`** — plain-data DTOs
  (interleaved primitive arrays). They are both the natural Java model and the
  marshalling boundary for the native adapter.
- **`TriangleMeshers`** — factory: `javaMesher()`, `nativeMesher()`,
  `validating(...)`, `differential(...)`.

Output triangles are linear (three corners). **Neighbour-slot convention:**
`neighborList[3*i + j]` is the triangle across the edge *opposite corner `j`* of
triangle `i`, or `-1` on a boundary. Global triangle order is not part of the
contract; this per-triangle slot alignment is.

---

## Architecture

```
com.acme.triangle            facade: TriangleMesher, DTOs, TriangleMeshers,
                             MeshContractException, DivergenceHandler
  .predicate                 Predicates       (robust orient2d / incircle)
  .contract                  MeshValidator    (the 6 structural invariants)
  .impl                      implementations & decorators
      DelaunayTriangulator             phase 1: Bowyer-Watson Delaunay
      ConstrainedDelaunayTriangulator  phase 2: segments, holes, regions
      JavaTriangleMesher               phase 3: + Ruppert refinement (the port)
      NativeTriangleMesher             JNA adapter to triangle.dll
      TriangulateIO, TriangleLibrary   JNA struct + binding
      ValidatingTriangleMesher         decorator: validate every output
      DifferentialTriangleMesher       decorator: run two, compare by contract
```

Everything is layered so each piece is validated **before** the next is built:

1. **`Predicates`** — the exact sign of the orient2d and incircle determinants,
   computed with `BigDecimal` (`new BigDecimal(double)` is exact). A wrong sign
   yields invalid topology, not merely a different mesh, so this is the
   foundation. Validated against `contract/predicates.txt` (298 cases, including
   near-degenerate ones that defeat naive `double`).

2. **`MeshValidator`** — checks any mesh against the six structural invariants
   the consumer depends on, using the robust predicates:
   1. topological validity (manifold, non-degenerate, consistent orientation);
   2. neighbour-slot semantics;
   3. constrained Delaunay (empty circumcircle off segments);
   4. segment recovery (every output segment is a real edge);
   5. holes & regions (no triangle covers a hole point; region attribute
      constant across non-segment edges);
   6. quality (minimum angle ≥ the requested bound).
   It is **dual-use**: the decorators call it at runtime; the tests call it as
   the acceptance oracle.

3. **The meshers** — see below.

4. **The decorators** — `ValidatingTriangleMesher` (throws on a contract
   violation; a canary) and `DifferentialTriangleMesher` (runs a primary and a
   reference, compares **by contract validity, not equality**, and reports
   divergence). These are the migration machinery.

---

## The two implementations

### `JavaTriangleMesher` — the pure-Java port

Built and validated phase by phase:

| Phase | Class | Algorithm |
|---|---|---|
| 1 | `DelaunayTriangulator` | Bowyer–Watson incremental insertion |
| 2 | `ConstrainedDelaunayTriangulator` | split crossing segments → Delaunay → recover segments by edge flips → restore constrained Delaunay → flood-fill carve holes/concavities → flood-fill region attributes |
| 3 | `JavaTriangleMesher` | + Ruppert/Üngör refinement (split encroached subsegments with concentric shells; insert off-centre Steiner points for below-bound triangles, deferring to subsegment splits; Miller–Pav–Walkington skip on fine-featured boundaries) |

Because contract equivalence frees the algorithm choice, this uses the simplest
correct algorithms (Bowyer–Watson, Ruppert/Üngör) rather than Triangle's
divide-and-conquer. All geometric tests use the robust `Predicates`.

It honours `minAngleDegrees` and **per-region maximum-area constraints** (the 4th
value of each `regionList` entry). A single *global* area bound (Triangle's `-a`
with no region) is not part of the target API.

### `NativeTriangleMesher` — JNA adapter

Marshals the input DTO into native memory, calls the native `triangulate()`
(loaded by JNA from the vendored `triangle.dll`), copies the mesh back, and frees
Triangle's allocations with `trifree`. Calls are serialized (native Triangle uses
process-global state). Triangle aborts the process on a fatal input error rather
than returning, so callers must supply valid geometry.

---

## Migration strategy

The two implementations are interchangeable behind `TriangleMesher`, proven by
`DifferentialTest` (every scenario plus a seeded fuzz of random PSLGs — each
mesher must honour the full contract). A safe rollout:

1. Route the consumer through `TriangleMesher` / `TriangleMeshers`.
2. Ship on `nativeMesher()` — works today, battle-tested.
3. Shadow-test with `differential(javaMesher(), nativeMesher())` on real inputs;
   the divergence handler logs (or throws) when the Java mesh is invalid.
4. Switch to `javaMesher()` for a pure-JVM result — no native dependency.

Each step is contract-checked, not byte-compared.

---

## Vendored artifacts and their provenance

These come from the reduced C Triangle reference repository and must be
regenerated there if `triangle.c` changes:

| File(s) | Source | Used by |
|---|---|---|
| `main/resources/win32-x86-64/triangle.dll` | `make shared` | `NativeTriangleMesher` (JNA) |
| `test/resources/contract/predicates.txt` | `predicate_oracle.c` | `PredicatesTest` |
| `test/resources/meshes/*.txt` | `golden_runner.c` (final) and `phase_runner.c` (`.delaunay`, `.cdt`) | `ContractTest`, `PhaseValidationTest` |

The mesh files are validated **structurally** (by `MeshValidator`), not matched
byte-for-byte, since a reimplementation produces a different valid mesh. They
exist to prove `MeshValidator` is a correct oracle at each phase.

`win32-x86-64` is JNA's resource prefix for 64-bit Windows. For other platforms,
build the corresponding shared library and place it under the matching prefix
(`linux-x86-64`, `darwin-aarch64`, …).

---

## Tests

`./gradlew test` runs, among others:

- `PredicatesTest` — predicate signs vs the oracle (298).
- `ContractTest` — `MeshValidator` against known-good meshes, plus a teeth-test
  per invariant (a broken mesh must be rejected).
- `PhaseValidationTest` — Delaunay / CDT phase meshes satisfy their phase
  contract.
- `DelaunayTriangulatorTest`, `ConstrainedDelaunayTriangulatorTest`,
  `JavaTriangleMesherTest` — the port, phase by phase, against the contract.
- `NativeTriangleMesherTest` — the JNA pipeline end to end.
- `DecoratorsTest`, `DifferentialTest` — the decorators and the
  java-vs-native fuzz.

`ScenarioFixtures` holds the shared input scenarios (a port of the C
`scenarios.c`); `MeshDump` parses the vendored reference meshes.

---

## Limitations & possible follow-ups

- **Performance.** Done, with no meaningful gap left to native. Filtered
  predicates (fast `double` estimate with an exact `BigDecimal` fallback),
  incremental refinement (local Bowyer–Watson insertion), maintained triangle
  adjacency (O(cavity) insertion), a worst-first bad-triangle queue, and a
  maintained encroachment index together took the hard q=33 fine-hole case from
  74 s to ~0.17 s — ~1.6× native, and *below* native's triangle count. On size we
  measured java-vs-native counts across the suite and sit at parity or below
  native almost everywhere, so free-vertex deletion (Chew) was evaluated and
  deliberately **not** pursued — high-risk for no measured benefit. The full path,
  steps, lessons, and the size analysis are in
  [docs/refinement-performance.md](docs/refinement-performance.md); the
  small-feature *termination* work behind it (concentric shells + the
  Miller–Pav–Walkington skip) is in
  [docs/refinement-small-features.md](docs/refinement-small-features.md).
- **A global (non-regional) area bound** is not part of the API (per-region max
  area *is* honoured).
- **Native platforms** other than Windows x64 need their shared library built
  and vendored.

---

## Licensing

The pure-Java source here is original work (it implements published algorithms,
validated against Triangle's output). It bundles a compiled build of Jonathan
Shewchuk's **Triangle** (`triangle.dll`) and Triangle-generated data; those
artifacts are covered by Triangle's license — free for non-commercial use with
copyright notices retained. See [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).
