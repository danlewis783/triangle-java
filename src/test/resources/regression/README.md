# Regression inputs

These JSON inputs were added from consuming-program failures and divergences
found during java-vs-native meshing and downstream processing. They are kept
here as focused regression fixtures so the same cases can be reproduced during
development and test runs.

## Contract divergence: primary mesher (Java) produced an invalid mesh

### `nested-c-sections-with-radii-2-materials.json`

Complex two-material geometry with many constrained segments and radiused
features. The pure-Java mesher diverged from the structural mesh contract on
this input, while the native reference remained the trusted baseline.

### `rect-section-2-offset-triangles-2-materials.json`

Small two-material section with an internal constrained boundary. The
pure-Java mesher diverged from the structural mesh contract on this input.
This is a compact reproducer for a consumer-visible failure in the same family
as the more complex multi-material cases.

## Consuming program exception with both meshers

### `eba-ex-problem-0-001-radii.json`

The consuming program threw:

`java.lang.IllegalStateException: No next OML edge for node 1:1.0,2.0`

This case fails downstream with both the pure-Java mesher and the JNA native
mesher, so it is not currently considered Java-mesher-specific. It appears to
exercise a consumer-visible boundary / segment-graph problem or an integration
assumption shared by both meshing paths.

## Downstream value differences after processing

### `rectangle-frame-thick-1.json`

Rectangle-frame case without a hole seed. The consuming program completed, but
downstream computed values differed from the expected/native-based result.

### `rectangle-frame-thick-with-hole.json`

Rectangle-frame case with a hole seed. The consuming program completed, but
downstream computed values differed from the expected/native-based result.

### `rectangle-frame-thin-1.json`

Thin rectangle-frame case without a hole seed. The consuming program
completed, but downstream computed values differed from the expected/native-based
result.

### `rectangle-frame-thin-with-hole.json`

Thin rectangle-frame case with a hole seed. The consuming program completed,
but downstream computed values differed from the expected/native-based result.

## Notes

These fixtures represent several kinds of regressions:

- **Java-mesher correctness problems**, where the Java output diverged from the
  mesh contract;
- **shared downstream failures**, where both the Java and native meshers lead
  to the same consuming-program exception; and
- **consumer-visible downstream differences**, where processing completed but
  produced different values.

They should be preserved as stable repro cases whenever the mesher,
segment-output semantics, or downstream integration behaviour changes.

## Findings (2026-07-01)

Every fixture here is now meshed and contract-validated on every test run, by
both meshers (`JavaTriangleMesherTest` /
`NativeTriangleMesherTest.capturedRegressionFixturesProduceContractValidMeshes`).
What the investigation found:

- **The two "contract divergences" were a validator gap, not a Java-mesher
  bug.** On `nested-c-sections-...` and `rect-section-2-offset-...`, the
  *native* mesher failed `MeshValidator` identically - near-identical
  below-bound angle lists, because both meshers apply the Miller-Pav-Walkington
  rule (triangle.c:4084): triangles wedged on a concentric shell across two
  input segments meeting at a small angle (here, the radiused two-material
  transitions) are deliberately left unsplit - no Delaunay refinement can fix
  an angle the input geometry imposes. `MeshValidator.checkQuality` now
  recognizes exactly that wedge (`wedgedAtAJoin`), and both meshers pass.

- **`eba-ex-problem-0-001-radii`'s downstream failure is an input
  segment-graph property.** Vertices 0 (1.0, 2.0) and 1 (-1.1e-16, 2.0) have
  *three* incident input segments each: the two-region interface (marker 2)
  meets the y=2 outer line there, and the marker-1 outer chain is
  discontinuous at vertex 0 (its continuations carry marker 2). An OML walk
  that follows marker-1 edges dead-ends at exactly that node - with either
  mesher, since both preserve input markers on output subsegments. The fix
  belongs in the input generation (marker assignment at the region junctions)
  or in the walker (handle junction nodes). Note also vertex 1's x of
  -1.110223e-16, an upstream artifact that likely wants flushing to 0.

- **The `rectangle-frame-*` value differences are expected mesh
  non-determinism.** Both meshers produce contract-valid meshes for all four;
  the meshes are different (java-vs-native triangulations differ legitimately,
  and shifted again when the Java mesher adopted Triangle's diametral-lens
  encroachment), so downstream computed values differ. If the consumer needs
  agreement, it needs mesh-independent tolerances rather than identical
  triangulations.