package com.acme.triangle.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.triangle.MeshInputException;
import com.acme.triangle.TriangleMesherInput;
import org.junit.jupiter.api.Test;

/**
 * The input contract: {@link InputValidator} names each structural defect of a
 * malformed {@link TriangleMesherInput}, and a valid input passes untouched.
 */
class InputValidatorTest {

    /** A minimal valid input: the unit square with its four boundary segments. */
    private static TriangleMesherInput square() {
        TriangleMesherInput in = new TriangleMesherInput();
        in.pointList = new double[]{0, 0, 1, 0, 1, 1, 0, 1};
        in.numberOfPoints = 4;
        in.segmentList = new int[]{0, 1, 1, 2, 2, 3, 3, 0};
        in.segmentMarkerList = new int[]{1, 1, 1, 1};
        in.numberOfSegments = 4;
        in.quiet = true;
        return in;
    }

    @Test
    void aValidInputHasNoViolations() {
        assertThat(InputValidator.validate(square())).isEmpty();
    }

    @Test
    void countLargerThanTheArrayIsNamed() {
        TriangleMesherInput in = square();
        in.numberOfPoints = 5;                    /* pointList only holds 4 */
        assertThat(InputValidator.validate(in))
                .anySatisfy(v -> assertThat(v).startsWith("points:").contains("numberOfPoints 5"));
    }

    @Test
    void tooFewPointsIsNamed() {
        TriangleMesherInput in = new TriangleMesherInput();
        in.pointList = new double[]{0, 0, 1, 0};
        in.numberOfPoints = 2;
        assertThat(InputValidator.validate(in))
                .anySatisfy(v -> assertThat(v).contains("at least 3 points"));
    }

    @Test
    void nonFiniteCoordinatesAreNamed() {
        TriangleMesherInput in = square();
        in.pointList[2] = Double.NaN;
        assertThat(InputValidator.validate(in))
                .anySatisfy(v -> assertThat(v).startsWith("points: point 1").contains("finite"));
    }

    @Test
    void duplicatePointsAreNamedWithBothIndices() {
        TriangleMesherInput in = square();
        in.pointList[6] = 0;                      /* point 3 becomes (0, 0) = point 0 */
        in.pointList[7] = 0;
        assertThat(InputValidator.validate(in))
                .anySatisfy(v -> assertThat(v).contains("point 3 duplicates point 0"));
    }

    @Test
    void outOfRangeSegmentEndpointIsNamed() {
        TriangleMesherInput in = square();
        in.segmentList[1] = 99;
        assertThat(InputValidator.validate(in))
                .anySatisfy(v -> assertThat(v).startsWith("segments: segment 0").contains("99"));
    }

    @Test
    void selfLoopSegmentIsNamed() {
        TriangleMesherInput in = square();
        in.segmentList[1] = 0;                    /* segment 0 becomes (0, 0) */
        assertThat(InputValidator.validate(in))
                .anySatisfy(v -> assertThat(v).contains("joins vertex 0 to itself"));
    }

    @Test
    void duplicateSegmentsAreNamedWithBothIndices() {
        TriangleMesherInput in = square();
        in.segmentList[6] = 1;                    /* segment 3 becomes (1, 0) = segment 0 */
        in.segmentList[7] = 0;
        assertThat(InputValidator.validate(in))
                .anySatisfy(v -> assertThat(v).contains("segment 3 duplicates segment 0"));
    }

    @Test
    void unsatisfiableAngleBoundIsNamed() {
        TriangleMesherInput in = square();
        in.minAngleDegrees = 60;
        assertThat(InputValidator.validate(in))
                .anySatisfy(v -> assertThat(v).startsWith("quality:").contains("unsatisfiable"));
    }

    @Test
    void requireValidThrowsWithTheViolationsAttached() {
        TriangleMesherInput in = square();
        in.segmentList[1] = 99;
        assertThatThrownBy(() -> InputValidator.requireValid(in))
                .isInstanceOf(MeshInputException.class)
                .hasMessageContaining("segments: segment 0")
                .satisfies(e -> assertThat(((MeshInputException) e).violations()).isNotEmpty());
    }
}
