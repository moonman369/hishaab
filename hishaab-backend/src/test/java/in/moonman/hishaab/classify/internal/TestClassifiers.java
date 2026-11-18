package in.moonman.hishaab.classify.internal;

import in.moonman.hishaab.classify.ClassifyService;

/** Test-only access to the real classifier without a Spring context. */
public final class TestClassifiers {

    private TestClassifiers() {
    }

    public static ClassifyService fromClasspath() {
        return new RuleBasedClassifier();
    }
}
