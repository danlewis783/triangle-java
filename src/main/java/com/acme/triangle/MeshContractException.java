package com.acme.triangle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Thrown when a mesh fails the structural contract (see
 * {@code com.acme.triangle.contract.MeshValidator}). Carries the list of
 * violation messages.
 */
public class MeshContractException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final List<String> violations;

    public MeshContractException(String message, List<String> violations) {
        super(message + ": " + violations);
        this.violations = Collections.unmodifiableList(new ArrayList<>(violations));
    }

    /** The contract violations that caused this exception. */
    public List<String> violations() {
        return violations;
    }
}
