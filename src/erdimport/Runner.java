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
  private static final String TITLE = "PlantUML Importer";

  private Runner() {}

  static void fromFiles() {
    try {
      JFileChooser fc = null;
      Object vm = viewManager();
      Object made = R.tryCall(vm, "createJFileChooser");
      fc = made instanceof JFileChooser ? (JFileChooser) made : new JFileChooser();
      fc.setDialogTitle("Choose PlantUML file(s)");
      fc.setMultiSelectionEnabled(true);
      fc.setFileFilter(new FileNameExtensionFilter("PlantUML files (*.puml, *.plantuml, *.pu, *.wsd, *.txt)", "puml", "plantuml", "pu", "wsd", "iuml", "txt"));
      String last = prefs("lastDir");
      if (last != null && new File(last).isDirectory()) fc.setCurrentDirectory(new File(last));
      if (fc.showOpenDialog(root()) != JFileChooser.APPROVE_OPTION) return;
      File[] files = fc.getSelectedFiles();
      if (files == null || files.length == 0) files = new File[] {fc.getSelectedFile()};
      if (files[0] != null && files[0].getParentFile() != null) prefs("lastDir", files[0].getParentFile().getAbsolutePath());
      run(read(files), true);
    } catch (Throwable t) {
      fail(null, t, true);
    }
  }

  static void fromPaste() {
    try {
      JTextArea area = new JTextArea(28, 90);
      area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
      JScrollPane pane = new JScrollPane(area);
      int ok = JOptionPane.showConfirmDialog(root(), new Object[] {"Paste PlantUML code (from @startuml to @enduml). ER, class and use case diagrams are imported:", pane},
          TITLE, JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
      if (ok != JOptionPane.OK_OPTION || area.getText().trim().length() == 0) return;
      List<Named> blocks = new ArrayList<Named>();
      for (UmlParser.Block b : UmlParser.split(area.getText())) blocks.add(new Named(b, "Pasted " + b.kind.label));
      run(blocks, true);
    } catch (Throwable t) {
      fail(null, t, true);
    }
  }

  /** A block with the name to use when it has no title. */
  static final class Named {
    final UmlParser.Block block;
    final String fallback;
    Named(UmlParser.Block block, String fallback) { this.block = block; this.fallback = fallback; }
  }

  static List<Named> read(File[] files) throws Exception {
    List<Named> out = new ArrayList<Named>();
    for (File f : files) {
      String text = new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8");
      String base = f.getName().replaceFirst("\\.[^.]+$", "");
      List<UmlParser.Block> blocks = UmlParser.split(text);
      for (int i = 0; i < blocks.size(); i++) out.add(new Named(blocks.get(i), blocks.size() == 1 ? base : base + " " + (i + 1)));
    }
    return out;
  }

  /** Imports the blocks: ER diagrams as tables, class and use case diagrams as UML. Also used from the command line. */
  static boolean run(List<Named> blocks, boolean interactive) {
    VpImporter erd = null;
    UmlImporter uml = null;
    SeqImporter seq = null;
    Object current = null;
    try {
      List<Puml.Diagram> erds = new ArrayList<Puml.Diagram>();
      List<Uml.Diagram> umls = new ArrayList<Uml.Diagram>();
      List<SeqParser.Diagram> seqs = new ArrayList<SeqParser.Diagram>();
      List<String> skipped = new ArrayList<String>();
      for (Named n : blocks) {
        Uml.Kind k = n.block.kind;
        if (k == Uml.Kind.ERD) erds.addAll(PumlParser.parse(n.block.text, n.fallback));
        else if (k == Uml.Kind.SEQUENCE) seqs.add(SeqParser.parse(n.block.text, n.fallback));
        else if (k != Uml.Kind.UNKNOWN) umls.add(UmlParser.parseAny(n.block, n.fallback));
        else skipped.add(n.fallback + ": the diagram type was not recognised");
      }
      if (erds.isEmpty() && umls.isEmpty() && seqs.isEmpty()) {
        StringBuilder b = new StringBuilder("Nothing to import. No PlantUML diagram was found between @startuml and @enduml.");
        for (String s : skipped) b.append("\n - ").append(s);
        show(b.toString(), JOptionPane.WARNING_MESSAGE, interactive);
        return false;
      }
      StringBuilder b = new StringBuilder();
      StringBuilder log = new StringBuilder();
      List<String> notes = new ArrayList<String>();
      if (!erds.isEmpty()) {
        erd = new VpImporter();
        current = erd;
        for (Puml.Diagram d : erds) erd.importDiagram(d);
        b.append("ER diagrams: ").append(erd.diagrams).append('\n');
        for (Puml.Diagram d : erds) b.append(" - ").append(d.title).append(" (").append(d.entities.size()).append(" tables, ").append(d.links.size()).append(" relationships)\n");
        b.append("Tables: ").append(erd.newTables).append(" created");
        if (erd.reusedTables > 0) b.append(", ").append(erd.reusedTables).append(" already in the project and reused");
        b.append(". Relationships: ").append(erd.newRels).append(" created");
        if (erd.reusedRels > 0) b.append(", ").append(erd.reusedRels).append(" reused");
        b.append(".\n\n");
        notes.addAll(erd.warnings);
        log.append(erd.log);
      }
      if (!umls.isEmpty() || !seqs.isEmpty()) {
        uml = new UmlImporter();
        current = uml;
        List<String> done = new ArrayList<String>();
        for (Uml.Diagram d : umls) {
          try { // one bad diagram should not stop the others
            uml.importDiagram(d);
            done.add(" - " + d.title + " (" + d.kind.label + ", " + d.nodes.size() + " elements, " + d.links.size() + " relationships)");
          } catch (Throwable t) {
            skipped.add(d.title + ": stopped while trying to " + uml.step + " (" + cause(t) + ")");
          }
        }
        if (!seqs.isEmpty()) {
          seq = new SeqImporter(uml);
          current = seq;
          for (SeqParser.Diagram d : seqs) {
            try {
              seq.importDiagram(d);
              done.add(" - " + d.title + " (sequence diagram, " + d.participants.size() + " lifelines, " + d.messages.size() + " messages)");
            } catch (Throwable t) {
              skipped.add(d.title + ": stopped while trying to " + seq.step + " (" + cause(t) + ")");
            }
          }
          notes.addAll(seq.warnings);
          log.append(seq.log);
        }
        if (!done.isEmpty()) {
          b.append("UML diagrams: ").append(done.size()).append('\n');
          for (String s : done) b.append(s).append('\n');
          if (!umls.isEmpty()) {
            b.append("Elements: ").append(uml.created).append(" created");
            if (uml.reused > 0) b.append(", ").append(uml.reused).append(" already in the project and reused");
            b.append(".\n");
          }
          b.append('\n');
        }
        notes.addAll(0, uml.warnings);
        log.insert(0, uml.log);
      }
      int made = (erd == null ? 0 : erd.diagrams) + (uml == null ? 0 : uml.diagrams) + (seq == null ? 0 : seq.diagrams);
      if (made == 0) {
        StringBuilder f = new StringBuilder("No diagram could be imported.\n");
        for (String sk : skipped) f.append(" - ").append(sk).append('\n');
        String path = writeLog(f.toString() + "\n\n" + log);
        if (path != null) f.append("\nLog: ").append(path);
        show(f.toString(), JOptionPane.WARNING_MESSAGE, interactive);
        return false;
      }
      Object first = uml != null && uml.firstDiagram != null ? uml.firstDiagram : seq != null && seq.firstDiagram != null ? seq.firstDiagram : null;
      if (first != null) R.tryCall(R.call(R.st("com.vp.plugin.ApplicationManager", "instance"), "getDiagramManager"), "openDiagram", first);
      else if (erd != null) erd.openFirst();
      b.append("The diagrams are in the Diagram Navigator. Shapes get a tidy starting layout; drag them as you like.");
      if (!skipped.isEmpty()) {
        b.append("\n\nNot imported:\n");
        for (String s : skipped) b.append(" - ").append(s).append('\n');
      }
      if (!notes.isEmpty()) {
        b.append("\n\nNotes:\n");
        for (String w : notes) b.append(" - ").append(w).append('\n');
      }
      String path = writeLog(b.toString() + "\n\n" + log);
      if (path != null) b.append("\n\nLog: ").append(path);
      show(b.toString(), skipped.isEmpty() ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.WARNING_MESSAGE, interactive);
      return true;
    } catch (Throwable t) {
      fail(current, t, interactive);
      return false;
    }
  }

  private static String cause(Throwable t) {
    Throwable c = t;
    while (c.getCause() != null && c.getCause() != c) c = c.getCause();
    return c.getMessage() != null ? c.getMessage() : c.toString();
  }

  private static void fail(Object im, Throwable t, boolean interactive) {
    StringWriter sw = new StringWriter();
    t.printStackTrace(new PrintWriter(sw));
    String step = im instanceof VpImporter ? ((VpImporter) im).step : im instanceof UmlImporter ? ((UmlImporter) im).step
        : im instanceof SeqImporter ? ((SeqImporter) im).step : "start";
    Object done = im instanceof VpImporter ? ((VpImporter) im).log : im instanceof UmlImporter ? ((UmlImporter) im).log
        : im instanceof SeqImporter ? ((SeqImporter) im).log : null;
    String msg = "Stopped while trying to " + step + ".\n\n" + t
        + "\n\nSend a screenshot of this message to get it fixed.\n\n"
        + (done == null ? "" : "Done before the error:\n" + done) + "\nDetails:\n" + sw;
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
    File f = new File(dir, "plantuml-importer.log");
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
