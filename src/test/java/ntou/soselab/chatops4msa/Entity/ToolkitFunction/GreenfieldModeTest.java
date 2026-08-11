package ntou.soselab.chatops4msa.Entity.ToolkitFunction;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "No namespace means greenfield" — the rule toolkit-depstate-start uses to pick
 * the analysis mode. No Spring context.
 */
class GreenfieldModeTest {

    @Test
    void blankOrAbsentNamespaceIsGreenfield() {
        assertTrue(DepstateToolkit.isGreenfield(null));
        assertTrue(DepstateToolkit.isGreenfield(""));
        assertTrue(DepstateToolkit.isGreenfield("   "));
        assertTrue(DepstateToolkit.isGreenfield("none"));
        assertTrue(DepstateToolkit.isGreenfield("None"));
        assertTrue(DepstateToolkit.isGreenfield("greenfield"));
    }

    @Test
    void aRealNamespaceIsRuntime() {
        assertFalse(DepstateToolkit.isGreenfield("default"));
        assertFalse(DepstateToolkit.isGreenfield("bank-of-anthos"));
        assertFalse(DepstateToolkit.isGreenfield("petclinic"));
    }
}
