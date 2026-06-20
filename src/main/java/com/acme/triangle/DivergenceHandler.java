package com.acme.triangle;

import java.util.List;

/**
 * Callback invoked by a differential mesher when the primary implementation's
 * contract validity diverges from the reference's (i.e. the primary produced an
 * invalid mesh). Lets callers choose shadow behaviour - log and continue - or
 * strict behaviour - throw - during a migration.
 */
@FunctionalInterface
public interface DivergenceHandler {

    /**
     * @param input              the input both meshers were given
     * @param primaryViolations  contract violations of the primary mesh (non-empty)
     * @param referenceViolations contract violations of the reference mesh
     */
    void onContractDivergence(TriangleMesherInput input,
                              List<String> primaryViolations,
                              List<String> referenceViolations);
}
