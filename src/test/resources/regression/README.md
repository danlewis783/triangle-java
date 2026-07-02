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