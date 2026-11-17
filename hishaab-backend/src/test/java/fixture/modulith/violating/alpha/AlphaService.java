package fixture.modulith.violating.alpha;

import fixture.modulith.violating.beta.internal.BetaInternals;

/** Deliberate boundary violation used by ModularityTest (AC-000-2). Not a Spring bean. */
public class AlphaService {

    public int reachIntoBeta() {
        return new BetaInternals().secret();
    }
}
