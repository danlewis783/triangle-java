package com.acme.triangle;

import com.google.common.collect.ImmutableList;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Thrown when a mesh fails the structural contract (see
 * {@code com.acme.triangle.contract.MeshValidator}). Carries the list of
 * violation messages and, when failure capture is enabled (the default), the
 * path the offending input was dumped to for post-mortem reproduction.
 */
public class MeshContractException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ImmutableList<String> violations;
    private volatile @Nullable String capturePath;

    public MeshContractException(String message, List<String> violations) {
        super(message + ": " + violations);
        this.violations = ImmutableList.copyOf(violations);
    }

    /** The contract violations that caused this exception. */
    public List<String> violations() {
        return violations;
    }

    /** Attach the post-mortem input-capture location (set once, by the failure
        capture machinery); it is appended to {@link #getMessage()}. */
    public void attachCapture(String path) {
        if (capturePath == null) {
            capturePath = path;
        }
    }

    /** Where the offending input was captured, or null if capture is disabled. */
    public @Nullable String capturePath() {
        return capturePath;
    }

    @Override
    public @Nullable String getMessage() {
        String path = capturePath;
        return path == null ? super.getMessage()
                : super.getMessage() + " [input captured: " + path + "]";
    }
}
