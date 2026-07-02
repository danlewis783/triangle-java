package com.acme.triangle;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.triangle.contract.InputValidator;
import com.acme.triangle.contract.MeshValidator;
import org.junit.jupiter.api.Test;

/**
 * The input builder produces exactly the flat DTO a careful hand-packer would:
 * counts derived, "none" marshalled as null arrays, and the result meshable.
 */
class TriangleMesherInputBuilderTest {

    @Test
    void buildsTheSameDtoAsHandPacking() {
        TriangleMesherInput.Builder b = TriangleMesherInput.builder();
        int p0 = b.point(0, 0);
        int p1 = b.point(2, 0);
        int p2 = b.point(2, 1);
        int p3 = b.point(0, 1);
        TriangleMesherInput in = b
                .segment(p0, p1, 1).segment(p1, p2, 1).segment(p2, p3, 1).segment(p3, p0, 1)
                .hole(1.7, 0.5)
                .region(0.3, 0.5, 2.0, 0.01)
                .minAngleDegrees(20)
                .build();

        assertThat(new int[]{p0, p1, p2, p3}).containsExactly(0, 1, 2, 3);
        assertThat(in.numberOfPoints).isEqualTo(4);
        assertThat(in.pointList).containsExactly(0, 0, 2, 0, 2, 1, 0, 1);
        assertThat(in.numberOfSegments).isEqualTo(4);
        assertThat(in.segmentList).containsExactly(0, 1, 1, 2, 2, 3, 3, 0);
        assertThat(in.segmentMarkerList).containsExactly(1, 1, 1, 1);
        assertThat(in.numberOfHoles).isEqualTo(1);
        assertThat(in.holeList).containsExactly(1.7, 0.5);
        assertThat(in.numberOfRegions).isEqualTo(1);
        assertThat(in.regionList).containsExactly(0.3, 0.5, 2.0, 0.01);
        assertThat(in.minAngleDegrees).isEqualTo(20);
        assertThat(in.quiet).as("builder defaults to quiet").isTrue();
        assertThat(InputValidator.validate(in)).isEmpty();
    }

    @Test
    void noneMarshalsToNullArraysAndZeroCounts() {
        TriangleMesherInput.Builder b = TriangleMesherInput.builder();
        b.point(0, 0);
        b.point(1, 0);
        b.point(0, 1);
        TriangleMesherInput in = b.build();

        assertThat(in.numberOfSegments).isZero();
        assertThat(in.segmentList).isNull();
        assertThat(in.segmentMarkerList).isNull();
        assertThat(in.numberOfHoles).isZero();
        assertThat(in.holeList).isNull();
        assertThat(in.numberOfRegions).isZero();
        assertThat(in.regionList).isNull();
    }

    @Test
    void aBuiltInputMeshesToAValidResult() {
        TriangleMesherInput.Builder b = TriangleMesherInput.builder();
        int p0 = b.point(0, 0);
        int p1 = b.point(4, 0);
        int p2 = b.point(4, 1);
        int p3 = b.point(0, 1);
        TriangleMesherInput in = b
                .segment(p0, p1, 1).segment(p1, p2, 1).segment(p2, p3, 1).segment(p3, p0, 1)
                .minAngleDegrees(20)
                .build();

        TriangleMesherOutput out = TriangleMeshers.javaMesher().mesh(in);

        assertThat(out.numberOfTriangles).isGreaterThan(2);
        assertThat(MeshValidator.validate(out, in)).isEmpty();
    }
}
