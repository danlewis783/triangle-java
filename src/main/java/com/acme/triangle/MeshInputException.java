package com.acme.triangle;

import com.google.common.collect.ImmutableList;
import org.jspecify.annotations.Nullable;

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
    private volatile @Nullable String capturePath;

    public MeshInputException(String message, List<String> violations) {
        super(message + ": " + violations);
        this.violations = ImmutableList.copyOf(violations);
    }

    /** The input-contract violations that caused this exception. */
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
