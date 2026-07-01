package com.acme.triangle.impl;

import com.acme.triangle.DivergenceHandler;
import com.acme.triangle.TriangleMesher;
import com.acme.triangle.TriangleMesher2;
import com.acme.triangle.TriangleMesherInput;
import com.acme.triangle.TriangleMesherInput2;
import com.acme.triangle.TriangleMesherOutput;
import com.acme.triangle.TriangleMesherOutput2;
import com.acme.triangle.contract.MeshValidator;
import java.util.List;

/**
 * Runs a primary mesher and a reference mesher on the same input, validates both
 * against the structural contract, and returns the primary's output. When the
 * primary's mesh is invalid, the {@link DivergenceHandler} is notified so the
 * caller can log (shadow mode) or throw (strict mode).
 * <p>
 * The comparison is deliberately contract-based, not output equality: two
 * correct meshers legitimately produce different valid meshes, so equality would
 * be the wrong bar. The reference's violations are passed along for context
 * (normally empty for a trusted reference).
 */
public final class DifferentialTriangleMesher implements TriangleMesher, TriangleMesher2 {

    private final TriangleMesher2 primary;
    private final TriangleMesher2 reference;
    private final DivergenceHandler handler;

    public DifferentialTriangleMesher(TriangleMesher2 primary, TriangleMesher2 reference,
                                      DivergenceHandler handler) {
        this.primary = primary;
        this.reference = reference;
        this.handler = handler;
    }

    @Override
    public TriangleMesherOutput2 mesh(TriangleMesherInput2 input) {
        TriangleMesherOutput2 primaryOut = primary.mesh(input);
        TriangleMesherOutput2 referenceOut = reference.mesh(input);

        /* Validation and the handler are defined over the flat DTO; convert once. */
        TriangleMesherInput flatInput = input.toFlat();
        List<String> primaryViolations = MeshValidator.validate(primaryOut.toFlat(), flatInput);
        if (!primaryViolations.isEmpty()) {
            List<String> referenceViolations = MeshValidator.validate(referenceOut.toFlat(), flatInput);
            handler.onContractDivergence(flatInput, primaryViolations, referenceViolations);
        }
        return primaryOut;
    }

    /** Flat entry point: repack to the modelled form this decorator compares in,
        then marshal the result back - the conversion lives on the DTOs. */
    @Override
    public TriangleMesherOutput mesh(TriangleMesherInput input) {
        return mesh(TriangleMesherInput2.from(input)).toFlat();
    }
}
