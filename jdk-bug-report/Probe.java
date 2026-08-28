package probe;

import java.net.URL;
import java.net.URLClassLoader;

public class Probe {
    public static void main(String[] args) {
        Module self = Probe.class.getModule();
        Module unnamed = new URLClassLoader("probe", new URL[0], null).getUnnamedModule();

        System.out.println("module      = " + self.getName()
                + " (automatic=" + self.getDescriptor().isAutomatic() + ")");
        System.out.println("canRead(java.logging) = "
                + self.canRead(java.util.logging.Logger.class.getModule()));
        System.out.println("canRead(unnamed)      = " + self.canRead(unnamed));

        if (!self.canRead(unnamed)) {
            System.out.println("FAIL: automatic module lost its ALL_UNNAMED read edge");
            System.exit(1);
        }
        System.out.println("OK");
    }
}
