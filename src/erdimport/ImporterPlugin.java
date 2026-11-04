package erdimport;

import com.vp.plugin.VPPlugin;
import com.vp.plugin.VPPluginInfo;

/** Plugin entry point. */
public class ImporterPlugin implements VPPlugin {
  static volatile java.io.File pluginDir;

  public void loaded(VPPluginInfo pluginInfo) {
    try { pluginDir = pluginInfo.getPluginDir(); } catch (Throwable ignore) { /* only used for the log */ }
  }

  public void unloaded() {}
}
