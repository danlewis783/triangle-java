package com.acme.triangle.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.triangle.DivergenceHandler;
import com.acme.triangle.MeshContractException;
import com.acme.triangle.TriangleMesher;
import com.acme.triangle.TriangleMesherInput;
import com.acme.triangle.TriangleMesherOutput;
import com.acme.triangle.TriangleMeshers;
import com.acme.triangle.contract.ScenarioFixtures;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Tests the validating and differential decorators and the factory. */
class DecoratorsTest {

    private final TriangleMesherInput validInput =
            ScenarioFixtures.byName("pslg_square").input;

    /** A mesher that returns a structurally invalid mesh (out-of-range corner). */
    private static final TriangleMesher BAD = input -> {
        TriangleMesherOutput o = new TriangleMesherOutput();
        o.numberOfPoints = 3;
        o.pointList = new double[]{0, 0, 1, 0, 0, 1};
        o.numberOfTriangles = 1;
        o.triangleList = new int[]{0, 1, 99};            /* 99 is out of range */
        o.neighborList = new int[]{-1, -1, -1};
        o.numberOfSegments = 0;
        o.segmentList = new int[0];
        return o;
    };

    @Test
    void validatingPassesAValidMeshThrough() {
        TriangleMesher m = TriangleMeshers.validating(TriangleMeshers.nativeMesher());
        assertThat(m.mesh(validInput).numberOfTriangles).isGreaterThan(0);
    }

    @Test
    void validatingThrowsOnAnInvalidMesh() {
        TriangleMesher m = TriangleMeshers.validating(BAD);
        assertThatThrownBy(() -> m.mesh(validInput))
                .isInstanceOf(MeshContractException.class);
    }

    @Test
    void differentialReturnsPrimaryWhenBothValid() {
        TriangleMesher m = TriangleMeshers.differential(
                TriangleMeshers.nativeMesher(), TriangleMeshers.nativeMesher());
        assertThat(m.mesh(validInput).numberOfTriangles).isGreaterThan(0);
    }

    @Test
    void differentialThrowsStrictlyWhenPrimaryDiverges() {
        TriangleMesher m = TriangleMeshers.differential(
                BAD, TriangleMeshers.nativeMesher());
        assertThatThrownBy(() -> m.mesh(validInput))
                .isInstanceOf(MeshContractException.class);
    }

    @Test
    void differentialNotifiesHandlerAndReturnsPrimaryInShadowMode() {
        List<String> captured = new ArrayList<>();
        DivergenceHandler shadow =
                (in, primaryViolations, refViolations) -> captured.addAll(primaryViolations);

        TriangleMesher m = TriangleMeshers.differential(
                BAD, TriangleMeshers.nativeMesher(), shadow);
        TriangleMesherOutput out = m.mesh(validInput);

        assertThat(captured).as("primary violations were reported").isNotEmpty();
        assertThat(out.triangleList).as("returns the primary (under-test) output")
                .containsExactly(0, 1, 99);
    }
}
