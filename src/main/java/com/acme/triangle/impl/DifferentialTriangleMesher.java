package com.acme.triangle.impl;

import com.acme.triangle.DivergenceHandler;
import com.acme.triangle.TriangleMesher;
import com.acme.triangle.TriangleMesherInput;
import com.acme.triangle.TriangleMesherOutput;
import com.acme.triangle.contract.MeshValidator;
import java.util.List;

/**
 * Runs a primary mesher and a reference mesher on the same input, validates both
 * against the structural contract, and returns the primary's output. When the
 * primary's mesh is invalid, the {@link DivergenceHandler} is notified so the
 * caller can log (shadow mode) or throw (strict mode).
 *
 * <p>The comparison is deliberately contract-based, not output equality: two
 * correct meshers legitimately produce different valid meshes, so equality would
 * be the wrong bar. The reference's violations are passed along for context
 * (normally empty for a trusted reference).
 */
public final class DifferentialTriangleMesher implements TriangleMesher {

    private final TriangleMesher primary;
    private final TriangleMesher reference;
    private final DivergenceHandler handler;

    public DifferentialTriangleMesher(TriangleMesher primary, TriangleMesher reference,
                                      DivergenceHandler handler) {
        this.primary = primary;
        this.reference = reference;
        this.handler = handler;
    }

    @Override
    public TriangleMesherOutput mesh(TriangleMesherInput input) {
        TriangleMesherOutput primaryOut = primary.mesh(input);
        TriangleMesherOutput referenceOut = reference.mesh(input);

        List<String> primaryViolations = MeshValidator.validate(primaryOut, input);
        if (!primaryViolations.isEmpty()) {
            List<String> referenceViolations = MeshValidator.validate(referenceOut, input);
            handler.onContractDivergence(input, primaryViolations, referenceViolations);
        }
        return primaryOut;
    }
}
