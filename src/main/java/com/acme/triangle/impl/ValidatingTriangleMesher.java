package com.acme.triangle.impl;

import com.acme.triangle.MeshContractException;
import com.acme.triangle.TriangleMesher;
import com.acme.triangle.TriangleMesher2;
import com.acme.triangle.TriangleMesherInput;
import com.acme.triangle.TriangleMesherInput2;
import com.acme.triangle.TriangleMesherOutput;
import com.acme.triangle.TriangleMesherOutput2;
import com.acme.triangle.contract.MeshValidator;
import java.util.List;

/**
 * Decorator that validates the wrapped mesher's output against the structural
 * contract and throws {@link MeshContractException} if it is violated. Useful as
 * a canary around an under-development implementation; wrapping a trusted
 * implementation is unnecessary.
 */
public final class ValidatingTriangleMesher implements TriangleMesher, TriangleMesher2 {

    private final TriangleMesher2 delegate;

    public ValidatingTriangleMesher(TriangleMesher2 delegate) {
        this.delegate = delegate;
    }

    @Override
    public TriangleMesherOutput2 mesh(TriangleMesherInput2 input) {
        TriangleMesherOutput2 out = delegate.mesh(input);
        List<String> violations = MeshValidator.validate(out.toFlat(), input.toFlat());
        if (!violations.isEmpty()) {
            throw new MeshContractException("mesh violates the structural contract",
                    violations);
        }
        return out;
    }

    /** Flat entry point: repack to the modelled form this decorator validates in,
        then marshal the result back - the conversion lives on the DTOs. */
    @Override
    public TriangleMesherOutput mesh(TriangleMesherInput input) {
        return mesh(TriangleMesherInput2.from(input)).toFlat();
    }
}
