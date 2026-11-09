package erdimport;

import com.vp.plugin.VPPlugin;
import com.vp.plugin.VPPluginCommandLineSupport;
import com.vp.plugin.VPPluginInfo;

/**
 * Plugin entry point. Also runs from Visual Paradigm's command line (scripts/Plugin.sh):
 *   Plugin.sh -project base.vpp -pluginid plantuml.importer -pluginargs "out.vpp diagram1.puml diagram2.puml"
 * imports the files into base.vpp and saves the result as out.vpp.
 */
public class ImporterPlugin implements VPPlugin, VPPluginCommandLineSupport {
  static volatile java.io.File pluginDir;

  public void loaded(VPPluginInfo pluginInfo) {
    try { pluginDir = pluginInfo.getPluginDir(); } catch (Throwable ignore) { /* only used for the log */ }
  }

  public void unloaded() {}

  public void invoke(String[] args) {
    R.loader = getClass().getClassLoader();
    java.util.List<String> a = new java.util.ArrayList<String>();
    for (String s : args) for (String p : s.trim().split("\\s+")) if (p.length() > 0) a.add(p);
    if (a.size() < 2) {
      System.out.println("Usage: -pluginargs \"OUTPUT.vpp FILE.puml [FILE.puml ...]\"");
      return;
    }
    try {
      java.io.File[] files = new java.io.File[a.size() - 1];
      for (int i = 1; i < a.size(); i++) files[i - 1] = new java.io.File(a.get(i));
      boolean ok = Runner.run(Runner.read(files), false);
      Object pm = R.call(R.st("com.vp.plugin.ApplicationManager", "instance"), "getProjectManager");
      Object saved = R.call(pm, "saveProjectAs", new java.io.File(a.get(0)));
      System.out.println("PlantUML Importer: " + (ok ? "imported" : "nothing imported") + ", saved=" + saved + " -> " + a.get(0));
    } catch (Throwable t) {
      t.printStackTrace(System.out);
    }
  }
}
