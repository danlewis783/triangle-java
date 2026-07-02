package com.acme.triangle;

import com.google.common.collect.ImmutableList;

import java.util.List;

/**
 * Thrown when a mesher input fails the input contract (see
 * {@code com.acme.triangle.contract.InputValidator}) - the fail-fast
 * counterpart of {@link MeshContractException}, raised before any meshing
 * happens. Carries the list of violation messages, each naming the offending
 * field or element.
 */
public class MeshInputException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    private final ImmutableList<String> violations;

    public MeshInputException(String message, List<String> violations) {
        super(message + ": " + violations);
        this.violations = ImmutableList.copyOf(violations);
    }

    /** The input-contract violations that caused this exception. */
    public List<String> violations() {
        return violations;
    }
}
