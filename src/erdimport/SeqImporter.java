package erdimport;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Writes parsed PlantUML sequence diagrams into the open Visual Paradigm project. */
final class SeqImporter {
  private static final String F = "com.vp.plugin.model.factory.IModelElementFactory";
  private static final int TOP = 40, HEAD = 50, ROW = 40, GAP = 70;

  final List<String> warnings = new ArrayList<String>();
  final StringBuilder log = new StringBuilder();
  String step = "start";
  int diagrams, messages;
  Object firstDiagram;
  private final Object dm, factory;
  private final UmlImporter names; // shares diagram-name bookkeeping

  SeqImporter(UmlImporter names) throws Exception {
    step = "connect to Visual Paradigm";
    Object app = R.st("com.vp.plugin.ApplicationManager", "instance");
    dm = R.call(app, "getDiagramManager");
    factory = R.st(F, "instance");
    this.names = names;
  }

  void importDiagram(SeqParser.Diagram d) throws Exception {
    for (String w : d.warnings) warn(d.title + ": " + w);
    step = "create diagram " + d.title;
    Object dia = R.call(dm, "createDiagram", R.field("com.vp.plugin.diagram.IDiagramTypeConstants", "DIAGRAM_TYPE_INTERACTION_DIAGRAM"));
    String diaName = names.freeDiagramName(d.title);
    R.call(dia, "setName", diaName);

    int bottom = TOP + HEAD + (d.rows + 1) * ROW + 20;
    Map<SeqParser.Participant, Object> models = new HashMap<SeqParser.Participant, Object>();
    Map<SeqParser.Participant, Object> shapes = new HashMap<SeqParser.Participant, Object>();
    Map<SeqParser.Participant, Integer> center = new HashMap<SeqParser.Participant, Integer>();
    int x = 60;
    for (SeqParser.Participant p : d.participants) {
      step = "create lifeline " + p.name;
      boolean actor = "actor".equals(p.kind);
      Object m = R.call(factory, actor ? "createInteractionActor" : "createInteractionLifeLine");
      R.call(m, "setName", p.name);
      if (!actor && !"participant".equals(p.kind)) R.tryCall(m, "addStereotype", p.kind); // boundary, control, entity, database ...
      for (String s : p.stereotypes) R.tryCall(m, "addStereotype", s);
      int w = Math.max(actor ? 60 : 100, p.name.length() * 8 + 30);
      int top = p.createdAt >= 0 ? rowY(p.createdAt) - 20 : TOP;
      int end = p.destroyedAt >= 0 ? rowY(p.destroyedAt) + 10 : bottom;
      Object shape = R.call(dm, "createDiagramElement", dia, m);
      R.call(shape, "setBounds", Integer.valueOf(x), Integer.valueOf(top), Integer.valueOf(w), Integer.valueOf(end - top));
      R.tryCall(shape, "setRequestResetCaption", Boolean.TRUE);
      R.tryCall(shape, "setRequestResetCaptionSize", Boolean.TRUE);
      models.put(p, m);
      shapes.put(p, shape);
      center.put(p, Integer.valueOf(x + w / 2));
      x += w + GAP;
      if (p.destroyedAt >= 0) R.tryCall(m, "setStopped", Boolean.TRUE);
    }
    int right = x;

    // activation bars
    Map<SeqParser.Activation, Object> acts = new HashMap<SeqParser.Activation, Object>();
    for (SeqParser.Activation a : d.activations) {
      step = "create activation on " + a.who.name;
      Object m = R.tryCall(factory, "createActivation");
      if (m == null) break;
      R.tryCall(models.get(a.who), "addActivation", m);
      int c = center.get(a.who).intValue() + a.depth * 5;
      int y1 = rowY(a.startRow) - 4, y2 = Math.max(y1 + 20, rowY(a.endRow) + 4);
      Object shape = R.tryCall(dm, "createDiagramElement", dia, m);
      if (shape == null) continue;
      R.tryCall(shape, "setBounds", Integer.valueOf(c - 5), Integer.valueOf(y1), Integer.valueOf(10), Integer.valueOf(y2 - y1));
      R.tryCall(shapes.get(a.who), "addChild", shape);
      acts.put(a, m);
    }

    for (SeqParser.Message msg : d.messages) {
      if (msg.from == null || msg.to == null) {
        warn(d.title + ": " + (msg.from == null ? "found" : "lost") + " message '" + msg.text + "' was skipped (it has no lifeline at one end)");
        continue;
      }
      step = "create message " + msg.text;
      Object m = R.call(factory, "createMessage");
      R.call(m, "setName", msg.text);
      R.call(m, "setFrom", models.get(msg.from));
      R.call(m, "setTo", models.get(msg.to));
      if (msg.number != null) R.tryCall(m, "setSequenceNumber", msg.number);
      if (msg.async) R.tryCall(m, "setAsynchronous", Boolean.TRUE);
      String at = msg.reply ? "createActionTypeReturn" : msg.create ? "createActionTypeCreate" : msg.destroy ? "createActionTypeDestroy" : null;
      if (at != null) { Object a = R.tryCall(factory, at); if (a != null) R.tryCall(m, "setActionType", a); }
      if (msg.create) R.tryCall(m, "setType", R.fieldOr("com.vp.plugin.model.IMessage", "TYPE_CREATE_MESSAGE", Integer.valueOf(2)));
      Object fromAct = activationAt(d, acts, msg.from, msg.row, false), toAct = activationAt(d, acts, msg.to, msg.row, true);
      if (fromAct != null) R.tryCall(m, "setFromActivation", fromAct);
      if (toAct != null) R.tryCall(m, "setToActivation", toAct);
      int y = rowY(msg.row);
      int fx = center.get(msg.from).intValue(), tx = center.get(msg.to).intValue();
      Point[] pts;
      if (msg.from == msg.to) {
        R.tryCall(m, "setType", R.fieldOr("com.vp.plugin.model.IMessage", "TYPE_SELF_MESSAGE", Integer.valueOf(3)));
        pts = new Point[] {new Point(fx, y), new Point(fx + 40, y), new Point(fx + 40, y + ROW / 2), new Point(fx, y + ROW / 2)};
      } else if (msg.create) {
        Object toShape = shapes.get(msg.to);
        int edge = num(R.tryCall(toShape, "getX"), tx) + (tx < fx ? num(R.tryCall(toShape, "getWidth"), 0) : 0);
        pts = new Point[] {new Point(fx, y), new Point(edge, y)};
      } else {
        pts = new Point[] {new Point(fx, y), new Point(tx, y)};
      }
      Object con = R.tryCall(dm, "createConnector", dia, m, shapes.get(msg.from), shapes.get(msg.to), pts);
      if (con == null) warn(d.title + ": could not draw the message '" + msg.text + "'");
      else R.tryCall(con, "setRequestResetCaption", Boolean.TRUE);
      messages++;
    }

    // combined fragments (alt, loop, opt ...), outermost first so inner ones sit on top
    List<SeqParser.Fragment> frags = new ArrayList<SeqParser.Fragment>(d.fragments);
    java.util.Collections.sort(frags, new java.util.Comparator<SeqParser.Fragment>() {
      public int compare(SeqParser.Fragment a, SeqParser.Fragment b) { return a.depth - b.depth; }
    });
    for (SeqParser.Fragment f : frags) {
      step = "create " + f.operator + " fragment";
      int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
      List<SeqParser.Participant> covered = new ArrayList<SeqParser.Participant>();
      for (SeqParser.Message msg : d.messages) {
        if (msg.row < f.startRow || msg.row > f.endRow) continue;
        for (SeqParser.Participant p : new SeqParser.Participant[] {msg.from, msg.to}) {
          if (p == null) continue;
          if (!covered.contains(p)) covered.add(p);
          int c = center.get(p).intValue();
          lo = Math.min(lo, c); hi = Math.max(hi, c);
        }
      }
      for (SeqParser.Participant p : f.over) { // ref over A, B
        if (!covered.contains(p)) covered.add(p);
        int c = center.get(p).intValue();
        lo = Math.min(lo, c); hi = Math.max(hi, c);
      }
      if (lo == Integer.MAX_VALUE) { // nothing inside: span every lifeline
        for (SeqParser.Participant p : d.participants) { int c = center.get(p).intValue(); lo = Math.min(lo, c); hi = Math.max(hi, c); }
        if (lo == Integer.MAX_VALUE) { lo = 60; hi = right - GAP; }
      }
      Object m;
      boolean ref = f.operator.equals("ref");
      if (ref) {
        m = R.call(factory, "createInteractionOccurrence");
        R.call(m, "setName", f.label);
        Object target = R.tryCall(factory, "createFrame"); // VP shows the name of the referenced frame
        if (target != null) { R.tryCall(target, "setName", f.label); R.tryCall(m, "setRefersTo", target); }
      } else {
        m = R.call(factory, "createCombinedFragment");
        R.tryCall(m, "setInteractionOperator", f.operator.equals("group") ? "seq" : f.operator);

        for (SeqParser.Operand o : f.operands) {
          Object op = R.tryCall(m, "createInteractionOperand");
          if (op == null) continue;
          if (o.guard != null && o.guard.length() > 0) { // a group's label is shown as its guard
            Object g = R.tryCall(factory, "createInteractionConstraint");
            if (g != null) { R.tryCall(g, "setConstraint", o.guard); R.tryCall(op, "setGuard", g); }
            else R.tryCall(op, "setName", o.guard);
          }
        }
      }
      for (SeqParser.Participant p : covered) R.tryCall(m, "addCoveredLifeLine", models.get(p));
      int pad = 50 - f.depth * 10;
      int fy = rowY(f.startRow) - 12, fh = rowY(f.endRow) - fy - 4;
      int fx = lo - pad - 20, fw = hi - lo + 2 * pad + 40;
      Object shape = R.call(dm, "createDiagramElement", dia, m);
      R.call(shape, "setBounds", Integer.valueOf(fx), Integer.valueOf(fy), Integer.valueOf(fw), Integer.valueOf(Math.max(30, fh)));
      R.tryCall(shape, "setRequestResetCaption", Boolean.TRUE);
      if (!ref) { // one shape per operand: draws the dashed divider and the [guard]
        Object ops = R.tryCall(m, "toInteractionOperandArray");
        int n = ops == null ? 0 : java.lang.reflect.Array.getLength(ops);
        for (int i = 0; i < n && i < f.operands.size(); i++) {
          SeqParser.Operand o = f.operands.get(i);
          int oy = i == 0 ? fy : rowY(o.startRow) - 12;
          int oh = (i == n - 1 ? fy + Math.max(30, fh) : rowY(f.operands.get(i + 1).startRow) - 12) - oy;
          Object os = R.tryCall(dm, "createDiagramElement", dia, java.lang.reflect.Array.get(ops, i));
          if (os == null) continue;
          R.tryCall(os, "setBounds", Integer.valueOf(fx), Integer.valueOf(oy), Integer.valueOf(fw), Integer.valueOf(Math.max(20, oh)));
          R.tryCall(shape, "addChild", os);
          R.tryCall(os, "setRequestResetCaption", Boolean.TRUE);
        }
      }
    }

    for (SeqParser.Note n : d.notes) {
      step = "create note";
      Object m = R.call(factory, "createNOTE");
      R.tryCall(m, "setDescription", n.text == null ? "" : n.text);
      int lines = n.text == null ? 1 : n.text.split("\n").length, longest = 0;
      if (n.text != null) for (String s : n.text.split("\n")) longest = Math.max(longest, s.length());
      int w = Math.max(100, Math.min(260, longest * 7 + 30)), h = 20 + 16 * lines;
      int c = n.over.isEmpty() ? 60 : center.get(n.over.get(0)).intValue();
      int nx = n.side.equals("left") ? c - w - 15 : n.side.equals("right") ? c + 15 : c - w / 2;
      if (n.side.equals("over") && n.over.size() > 1) {
        int c2 = center.get(n.over.get(n.over.size() - 1)).intValue();
        nx = Math.min(c, c2) - 30; w = Math.abs(c2 - c) + 60;
      }
      Object shape = R.call(dm, "createDiagramElement", dia, m);
      R.call(shape, "setBounds", Integer.valueOf(nx), Integer.valueOf(rowY(n.row) + 6), Integer.valueOf(w), Integer.valueOf(h));
      R.tryCall(shape, "fitSize");
    }
    diagrams++;
    if (firstDiagram == null) firstDiagram = dia;
    log.append("diagram ").append(diaName).append(" (sequence diagram): ").append(d.participants.size()).append(" lifelines, ")
        .append(d.messages.size()).append(" messages\n");
  }

  /** The activation of this lifeline that a message at this row starts from (or, for the receiver, starts at). */
  private static Object activationAt(SeqParser.Diagram d, Map<SeqParser.Activation, Object> acts, SeqParser.Participant p, int row, boolean receiving) {
    Object best = null;
    int depth = -1;
    for (SeqParser.Activation a : d.activations) {
      if (a.who != p || acts.get(a) == null) continue;
      boolean hit = receiving ? a.startRow == row : a.startRow <= row && row <= a.endRow;
      if (hit && a.depth > depth) { best = acts.get(a); depth = a.depth; }
    }
    return best;
  }

  private static int rowY(int row) { return TOP + HEAD + 30 + row * ROW; }

  private static int num(Object o, int fallback) { return o instanceof Number ? ((Number) o).intValue() : fallback; }

  private void warn(String s) { if (!warnings.contains(s)) warnings.add(s); }
}
