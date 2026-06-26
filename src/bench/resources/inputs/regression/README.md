# Benchmark input corpus

The 47 captured `triangle-mesher-input` documents here are the real-world /
fuzz-derived inputs for the micro-benchmark:

```
gradlew bench --args="src/bench/resources/inputs/regression"
```

All are at `minAngleDegrees = 33` — the bound that drove the refinement work (see
`docs/refinement-performance.md`). One of them, `rectangle-solid-with-hole.json`,
is the captured consumer case and is also a JUnit regression fixture
(`JavaTriangleMesherTest.refinesTheCapturedFineHoleRegressionAtQ33`, loaded from a
copy under `src/test/resources/regression/`).

The bench discovers files by directory scan (`*.json`), so they can be added or
removed freely — this README and the filenames are the only catalogue. Each file
is a versioned document whose `input` holds the interleaved `pointList` /
`segmentList` / `holeList` / `regionList` of a `TriangleMesherInput`.

## Filename convention

Names describe the geometry deduced from the point + segment data:

- `rect-WxH`, `circle-Ngon-rR`, `poly-Ngon` — the outer boundary shape.
- `ring-rOUT-rIN` — an annulus (circle with a concentric circular **hole**).
- `circles-rOUT-rIN` — two concentric circles, the inner one a constraint (no hole).
- `-{shape}-hole` — an interior loop that is removed (a hole).
- `-{shape}-inner` — an inscribed interior loop kept as a constraint (same region
  on both sides).
- `-Nreg` — the domain is split into N region attributes.
- `subdivision-Npts` — a multi-region planar subdivision whose interior is not
  cleanly a nested loop, so it is named by size rather than shape.
- `p` stands in for the decimal point (`r0p25` = radius 0.25).

## The corpus

| family | what it is | files |
|---|---|---|
| **Rectangles** | axis-aligned boxes — the consumer domain footprints, plus rectangular holes and inscribed rectangles | `rect-2x1`, `rect-4x4`, `rect-4x4-2reg`, `rect-3x4-rect-1x2-hole`, `rect-3x4-rect-1x2-inner`, `rect-3x4-rect-2p5x3p5-hole`, `rect-3x4-rect-2p5x3p5-inner`, `rect-3x4-poly-30gon-inner` |
| **Rectangle + circular hole** | a 2×1 rectangle around a 256-segment circle (r = 0.25 @ (1, 0.5)) — the q=33 regression case and two sibling captures | `rectangle-solid-with-hole`, `rect-2x1-circle-256gon-r0p25-hole`, `rect-2x1-circle-256gon-r0p25-inner` |
| **Disks, rings, concentric circles** | a filled disk; thin / medium / thick annuli (circular hole); concentric circle pairs (inner as a constraint, not a hole) | `circle-256gon-r1`, `ring-r0p51-r0p5`, `ring-r0p625-r0p5`, `ring-r1p25-r0p5`, `circles-r0p51-r0p5`, `circles-r0p625-r0p5`, `circles-r1p25-r0p5` |
| **Faceted panels** | many-sided (≈256-gon) outlines with many-sided holes or inscribed loops | `poly-256gon`, `poly-256gon-poly-216gon-hole`, `poly-256gon-poly-216gon-inner`, `poly-256gon-poly-268gon-hole`, `poly-256gon-poly-268gon-inner` |
| **Random polygons** (differential fuzz) | random simple polygons from the java-vs-native fuzz; the `-2reg` ones are split into two regions | `poly-6gon-01`…`-04`, `poly-8gon-01`…`-04`, `poly-9gon`, `poly-10gon`, `poly-11gon`, `poly-27gon`, `poly-33gon`, `poly-43gon`, `poly-49gon`, `poly-58gon`, `poly-130gon-01`…`-03`, `poly-38gon-2reg`, `poly-47gon-2reg` |
| **Multi-region subdivisions** | planar subdivisions partitioned into regions by internal polylines, named by size | `subdivision-12pts-2reg`, `subdivision-312pts-2reg`, `subdivision-312pts-2reg-2holes` |

(`.json` extensions omitted above.) The shapes were reconstructed straight from
the coordinates — walking `segmentList` into closed loops and classifying each by
corner count, axis-alignment, and radius uniformity.
