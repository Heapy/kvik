package fixture;

import org.junit.platform.engine.*;
import org.junit.platform.engine.discovery.ClassSelector;
import org.junit.platform.engine.support.descriptor.*;

/** Exercises actual Platform callbacks, without assuming an engine's proprietary retry behavior. */
public final class RetryEngine implements TestEngine {
    public String getId() { return "kotest"; }
    public TestDescriptor discover(EngineDiscoveryRequest request, UniqueId id) {
        String selected = request.getSelectorsByType(ClassSelector.class).get(0).getClassName();
        EngineDescriptor root = new EngineDescriptor(id, selected);
        root.addChild(new AbstractTestDescriptor(id.append("test", "repeated"), "Repeated display") {
            @Override public Type getType() { return Type.TEST; }
        });
        return root;
    }
    public void execute(ExecutionRequest request) {
        var root = request.getRootTestDescriptor();
        var test = root.getChildren().iterator().next();
        var listener = request.getEngineExecutionListener();
        listener.executionStarted(root);
        boolean capped = root.getDisplayName().endsWith("Limit");
        int count = capped ? 1001 : 2;
        for (int n = 0; n < count; n++) {
            if (n == 1 && root.getDisplayName().endsWith("Skip")) {
                listener.executionSkipped(test, "retry skipped");
            } else {
                listener.executionStarted(test);
                listener.executionFinished(test, !capped && n == 0 ?
                    TestExecutionResult.failed(new AssertionError("first attempt failed")) : TestExecutionResult.successful());
            }
        }
        listener.executionFinished(root, TestExecutionResult.successful());
    }
}
