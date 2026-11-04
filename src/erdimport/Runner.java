package erdimport;

import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.prefs.Preferences;

import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.filechooser.FileNameExtensionFilter;

/** Asks for the PlantUML, runs the import and reports the result. */
final class Runner {
  private static final String TITLE = "PlantUML ERD Importer";

  private Runner() {}

  static void fromFiles() {
    try {
      JFileChooser fc = null;
      Object vm = viewManager();
      Object made = R.tryCall(vm, "createJFileChooser");
      fc = made instanceof JFileChooser ? (JFileChooser) made : new JFileChooser();
      fc.setDialogTitle("Choose PlantUML ERD file(s)");
      fc.setMultiSelectionEnabled(true);
      fc.setFileFilter(new FileNameExtensionFilter("PlantUML files (*.puml, *.plantuml, *.pu, *.wsd, *.txt)", "puml", "plantuml", "pu", "wsd", "iuml", "txt"));
      String last = prefs("lastDir");
      if (last != null && new File(last).isDirectory()) fc.setCurrentDirectory(new File(last));
      if (fc.showOpenDialog(root()) != JFileChooser.APPROVE_OPTION) return;
      File[] files = fc.getSelectedFiles();
      if (files == null || files.length == 0) files = new File[] {fc.getSelectedFile()};
      if (files[0] != null && files[0].getParentFile() != null) prefs("lastDir", files[0].getParentFile().getAbsolutePath());
      List<Puml.Diagram> all = new ArrayList<Puml.Diagram>();
      for (File f : files) {
        String text = new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8");
        String base = f.getName().replaceFirst("\\.[^.]+$", "");
        all.addAll(PumlParser.parse(text, base));
      }
      run(all, true);
    } catch (Throwable t) {
      fail(null, t, true);
    }
  }

  static void fromPaste() {
    try {
      JTextArea area = new JTextArea(28, 90);
      area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
      JScrollPane pane = new JScrollPane(area);
      int ok = JOptionPane.showConfirmDialog(root(), new Object[] {"Paste the PlantUML ERD code (from @startuml to @enduml):", pane},
          TITLE, JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
      if (ok != JOptionPane.OK_OPTION || area.getText().trim().length() == 0) return;
      run(PumlParser.parse(area.getText(), "Pasted ERD"), true);
    } catch (Throwable t) {
      fail(null, t, true);
    }
  }

  /** Imports already-parsed diagrams. Also used by the tests. */
  static VpImporter run(List<Puml.Diagram> diagrams, boolean interactive) {
    VpImporter im = null;
    try {
      if (diagrams.isEmpty()) {
        show("No entities were found. The importer reads PlantUML ER diagrams: 'entity' blocks and lines such as  A ||--o{ B.",
            JOptionPane.WARNING_MESSAGE, interactive);
        return null;
      }
      im = new VpImporter();
      for (Puml.Diagram d : diagrams) im.importDiagram(d);
      im.openFirst();
      StringBuilder b = new StringBuilder();
      b.append("Imported ").append(im.diagrams).append(im.diagrams == 1 ? " ER diagram" : " ER diagrams").append(":\n");
      for (Puml.Diagram d : diagrams) b.append(" - ").append(d.title).append(" (").append(d.entities.size()).append(" tables, ").append(d.links.size()).append(" relationships)\n");
      b.append("\nTables: ").append(im.newTables).append(" created");
      if (im.reusedTables > 0) b.append(", ").append(im.reusedTables).append(" were already in the project and were reused");
      b.append(".\nRelationships: ").append(im.newRels).append(" created");
      if (im.reusedRels > 0) b.append(", ").append(im.reusedRels).append(" were already in the project and were reused");
      b.append(".\n\nThe diagrams are in the Diagram Navigator under Entity Relationship Diagram.\n")
          .append("If any boxes overlap, just drag them apart.");
      if (!im.warnings.isEmpty()) {
        b.append("\n\nNotes:\n");
        for (String w : im.warnings) b.append(" - ").append(w).append('\n');
      }
      String path = writeLog(b.toString() + "\n\n" + im.log);
      if (path != null) b.append("\n\nLog: ").append(path);
      show(b.toString(), JOptionPane.INFORMATION_MESSAGE, interactive);
    } catch (Throwable t) {
      fail(im, t, interactive);
    }
    return im;
  }

  private static void fail(VpImporter im, Throwable t, boolean interactive) {
    StringWriter sw = new StringWriter();
    t.printStackTrace(new PrintWriter(sw));
    String msg = "Stopped while trying to " + (im == null ? "start" : im.step) + ".\n\n" + t
        + "\n\nSend a screenshot of this message to get it fixed.\n\n"
        + (im == null ? "" : "Done before the error:\n" + im.log) + "\nDetails:\n" + sw;
    String path = writeLog(msg);
    if (path != null) msg = "Log: " + path + "\n\n" + msg;
    show(msg, JOptionPane.ERROR_MESSAGE, interactive);
  }

  private static Object viewManager() {
    try { return R.call(R.st("com.vp.plugin.ApplicationManager", "instance"), "getViewManager"); } catch (Throwable t) { return null; }
  }

  private static Component root() {
    Object c = R.tryCall(viewManager(), "getRootFrame");
    return c instanceof Component ? (Component) c : null;
  }

  private static void show(String text, int type, boolean interactive) {
    if (!interactive || GraphicsEnvironment.isHeadless()) { System.out.println(text); return; }
    JTextArea area = new JTextArea(text);
    area.setEditable(false);
    area.setLineWrap(true);
    area.setWrapStyleWord(true);
    JScrollPane pane = new JScrollPane(area);
    pane.setPreferredSize(new Dimension(640, type == JOptionPane.ERROR_MESSAGE ? 420 : 280));
    JOptionPane.showMessageDialog(root(), pane, TITLE, type);
  }

  private static String writeLog(String text) {
    File dir = ImporterPlugin.pluginDir;
    if (dir == null || !dir.isDirectory()) dir = new File(System.getProperty("user.home", "."));
    File f = new File(dir, "plantuml-erd-importer.log");
    Writer w = null;
    try {
      w = new OutputStreamWriter(new FileOutputStream(f), "UTF-8");
      w.write(text);
      return f.getAbsolutePath();
    } catch (Throwable e) {
      return null;
    } finally {
      if (w != null) try { w.close(); } catch (Throwable ignore) { /* ignore */ }
    }
  }

  private static String prefs(String key) {
    try { return Preferences.userNodeForPackage(Runner.class).get(key, null); } catch (Throwable t) { return null; }
  }

  private static void prefs(String key, String value) {
    try { Preferences.userNodeForPackage(Runner.class).put(key, value); } catch (Throwable ignore) { /* optional */ }
  }
}
