package com.acme.triangle.impl;

import com.acme.triangle.MeshContractException;
import com.acme.triangle.TriangleMesher;
import com.acme.triangle.TriangleMesherInput;
import com.acme.triangle.TriangleMesherOutput;
import com.acme.triangle.contract.MeshValidator;
import java.util.List;

/**
 * Decorator that validates the wrapped mesher's output against the structural
 * contract and throws {@link MeshContractException} if it is violated. Useful as
 * a canary around an under-development implementation; wrapping a trusted
 * implementation is unnecessary.
 */
public final class ValidatingTriangleMesher implements TriangleMesher {

    private final TriangleMesher delegate;

    public ValidatingTriangleMesher(TriangleMesher delegate) {
        this.delegate = delegate;
    }

    @Override
    public TriangleMesherOutput mesh(TriangleMesherInput input) {
        TriangleMesherOutput out = delegate.mesh(input);
        List<String> violations = MeshValidator.validate(out, input);
        if (!violations.isEmpty()) {
            throw new MeshContractException("mesh violates the structural contract",
                    violations);
        }
        return out;
    }
}
