package fixture;

import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;

public final class FixtureAgent {
    public static void premain(String options, Instrumentation instrumentation) throws Exception {
        String[] parts = options.split("\\|", -1);
        String config = Files.readString(Path.of(parts[0]));
        System.setProperty("kvik.agent", config);
        System.setProperty("kvik.agent.mode", parts[2]);
        if (parts[2].equals("missing")) return;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                Path output = Path.of(parts[1]);
                Files.writeString(output, config + "\n" + System.getProperty("kvik.testRan", "false"));
                if (parts[2].equals("extra")) Files.writeString(output.resolveSibling("undeclared.txt"), "unexpected");
            } catch (Exception failure) {
                throw new RuntimeException(failure);
            }
        }));
    }
}
