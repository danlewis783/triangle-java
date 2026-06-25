package com.acme.triangle.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import com.acme.triangle.TriangleMesher;
import com.acme.triangle.TriangleMesherInput;
import com.acme.triangle.TriangleMesherOutput;
import com.acme.triangle.TriangleMeshers;
import com.acme.triangle.contract.MeshValidator;
import com.acme.triangle.contract.ScenarioFixtures;
import com.acme.triangle.contract.ScenarioFixtures.Scenario;
import com.acme.triangle.io.TriangleJson;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.DynamicTest;

/**
 * Phase-3 port test: the full pure-Java {@link JavaTriangleMesher} (constrained
 * Delaunay + Ruppert refinement) must satisfy the complete contract - including
 * the minimum-angle quality bound - on every scenario, and must actually refine
 * a mesh that starts below the bound.
 */
class JavaTriangleMesherTest {

    private final TriangleMesher mesher = TriangleMeshers.javaMesher();

    @TestFactory
    List<DynamicTest> scenariosSatisfyTheFullContract() {
        List<DynamicTest> tests = new ArrayList<>();
        for (Scenario s : ScenarioFixtures.all()) {
            tests.add(dynamicTest(s.name, () -> {
                TriangleMesherOutput o = mesher.mesh(s.input);
                assertThat(MeshValidator.validate(o, s.input))
                        .as("contract violations for %s", s.name)
                        .isEmpty();
            }));
        }
        return tests;
    }

    @Test
    void refinesASliverBelowTheAngleBound() {
        /* A 4x1 rectangle: the constrained Delaunay diagonal makes a ~14 degree
           triangle, so a 20 degree bound forces Ruppert refinement. */
        TriangleMesherInput in = new TriangleMesherInput();
        in.pointList = new double[]{0, 0, 4, 0, 4, 1, 0, 1};
        in.numberOfPoints = 4;
        in.segmentList = new int[]{0, 1, 1, 2, 2, 3, 3, 0};
        in.segmentMarkerList = new int[]{1, 1, 1, 1};
        in.numberOfSegments = 4;
        in.minAngleDegrees = 20;
        in.quiet = true;

        TriangleMesherOutput o = mesher.mesh(in);

        assertThat(o.numberOfTriangles)
                .as("refinement inserted Steiner points")
                .isGreaterThan(2);
        assertThat(MeshValidator.validate(o, in))
                .as("refined sliver violations")
                .isEmpty();
    }

    @Test
    void refinesAFacetedHoleAtAHighAngleBound() {
        /* A finely-faceted hole at a high angle bound is the case that made plain
           refinement cascade without terminating. Concentric-shell segment
           splitting plus the Miller-Pav-Walkington skip rule must let it converge
           to a contract-valid mesh. */
        int holeSides = 48;
        int outerPoints = 4;
        int total = outerPoints + holeSides;
        double[] pts = new double[2 * total];
        pts[0] = 0; pts[1] = 0; pts[2] = 2; pts[3] = 0;
        pts[4] = 2; pts[5] = 1; pts[6] = 0; pts[7] = 1;
        double cx = 1.0, cy = 0.5, radius = 0.22;
        for (int i = 0; i < holeSides; i++) {
            double angle = 2.0 * Math.PI * i / holeSides;
            pts[2 * (outerPoints + i)] = cx + radius * Math.cos(angle);
            pts[2 * (outerPoints + i) + 1] = cy + radius * Math.sin(angle);
        }
        int segs = outerPoints + holeSides;
        int[] sl = new int[2 * segs];
        int[] mk = new int[segs];
        sl[0] = 0; sl[1] = 1; sl[2] = 1; sl[3] = 2; sl[4] = 2; sl[5] = 3; sl[6] = 3; sl[7] = 0;
        for (int i = 0; i < 4; i++) {
            mk[i] = 1;
        }
        for (int i = 0; i < holeSides; i++) {
            int s = outerPoints + i;
            sl[2 * s] = outerPoints + i;
            sl[2 * s + 1] = outerPoints + ((i + 1) % holeSides);
            mk[s] = 2;
        }

        TriangleMesherInput in = new TriangleMesherInput();
        in.pointList = pts;
        in.numberOfPoints = total;
        in.segmentList = sl;
        in.segmentMarkerList = mk;
        in.numberOfSegments = segs;
        in.holeList = new double[]{cx, cy};
        in.numberOfHoles = 1;
        in.minAngleDegrees = 33;
        in.quiet = true;

        TriangleMesherOutput o = mesher.mesh(in);

        assertThat(MeshValidator.validate(o, in))
                .as("faceted-hole q=33 violations")
                .isEmpty();
    }

    @Test
    void refinesTheCapturedFineHoleRegressionAtQ33() throws URISyntaxException {
        /* The real captured consumer input - a 2x1 rectangle around a 256-facet
           hole at q=33 with a per-region max area - is the case that first
           diverged, then merely crawled, and drove the whole
           refinement-performance effort. Guard it on every test run: the
           pure-Java mesher must converge to a fully contract-valid mesh. (The
           same document is the bench driver under
           src/bench/resources/inputs/regression; this is a frozen copy.) */
        URL fixture = getClass().getResource("/regression/rectangle-solid-with-hole.json");
        assertThat(fixture)
                .as("captured q=33 regression fixture on the test classpath")
                .isNotNull();
        TriangleMesherInput in = TriangleJson.readInput(Paths.get(fixture.toURI()));

        TriangleMesherOutput o = mesher.mesh(in);

        assertThat(MeshValidator.validate(o, in))
                .as("captured fine-hole q=33 violations")
                .isEmpty();
        /* Size-regression guard, not a contract requirement: native makes 2,745
           triangles and we make ~2,644. A blow-up back toward the pre-refinement
           ~8,000 is worth catching; the ceiling is deliberately generous so an
           ordinary ordering change does not trip it. */
        assertThat(o.numberOfTriangles)
                .as("captured fine-hole q=33 triangle count")
                .isLessThan(4000);
    }
}
