package erdimport;

import java.awt.Dimension;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Starting positions for class and use case diagrams. Plain Java: no Visual Paradigm types. */
final class Layout {
  private Layout() {}

  private static final int GAP = 70, PAD = 30, TAB = 40;

  // ------------------------------------------------------------------ sizes

  static Dimension size(Uml.Node n) {
    if (Uml.ACTOR.equals(n.kind)) return new Dimension(Math.max(40, n.name.length() * 7), 90);
    if (Uml.USECASE.equals(n.kind)) {
      int w = Math.max(130, Math.min(260, n.name.length() * 8 + 40));
      int lines = (int) Math.ceil(n.name.length() * 8.0 / (w - 30));
      return new Dimension(w, Math.max(50, 24 + 16 * lines));
    }
    if (Uml.NOTE.equals(n.kind)) {
      String t = n.text == null ? "" : n.text;
      int longest = 0, lines = 0;
      for (String l : t.split("\n")) { longest = Math.max(longest, l.length()); lines++; }
      return new Dimension(Math.max(120, Math.min(300, longest * 7 + 30)), Math.max(50, 20 + 16 * lines));
    }
    int chars = n.name.length() + 4;
    for (String s : n.stereotypes) chars = Math.max(chars, s.length() + 6);
    for (Uml.Member m : n.members) chars = Math.max(chars, text(m).length() + 2);
    for (String l : n.literals) chars = Math.max(chars, l.length() + 2);
    for (String sl : n.slots) chars = Math.max(chars, sl.length() + 2);
    int rows = n.members.size() + n.literals.size() + n.slots.size();
    int head = 30 + (n.stereotypes.isEmpty() && !Uml.INTERFACE.equals(n.kind) && !Uml.ENUM.equals(n.kind) ? 0 : 16);
    return new Dimension(Math.max(120, (int) (chars * 7.2) + 20), head + 18 * Math.max(1, rows) + 14);
  }

  private static String text(Uml.Member m) {
    StringBuilder b = new StringBuilder(m.name);
    if (m.method) {
      b.append('(');
      for (Uml.Param p : m.params) b.append(p.name).append(p.type == null ? "" : " : " + p.type).append(", ");
      b.append(')');
    }
    if (m.type != null) b.append(" : ").append(m.type);
    return b.toString();
  }

  // ------------------------------------------------------------------ class diagrams

  /** A grid per container (packages hold their classes), packages sized to fit what they hold. */
  static Map<Uml.Node, Rectangle> classes(Uml.Diagram d) {
    Map<Uml.Node, Rectangle> out = new LinkedHashMap<Uml.Node, Rectangle>();
    Map<Uml.Node, Dimension> sizes = new HashMap<Uml.Node, Dimension>();
    measure(d, null, sizes);
    place(d, null, 40, 40, sizes, out);
    return out;
  }

  private static List<Uml.Node> children(Uml.Diagram d, Uml.Node parent) {
    List<Uml.Node> out = new ArrayList<Uml.Node>();
    for (Uml.Node n : d.nodes) if (n.parent == parent) out.add(n);
    return out;
  }

  /** Size of the grid holding the parent's children (for null: the whole diagram). */
  private static Dimension measure(Uml.Diagram d, Uml.Node parent, Map<Uml.Node, Dimension> sizes) {
    List<Uml.Node> kids = children(d, parent);
    for (Uml.Node k : kids) {
      if (k.isContainer()) {
        Dimension inner = measure(d, k, sizes);
        sizes.put(k, new Dimension(Math.max(160, inner.width + 2 * PAD), Math.max(90, inner.height + PAD + TAB)));
      } else {
        sizes.put(k, size(k));
      }
    }
    int per = Math.max(1, (int) Math.ceil(Math.sqrt(kids.size())));
    int w = 0, h = 0;
    for (int row = 0; row * per < kids.size(); row++) {
      int rw = 0, rh = 0;
      for (int i = row * per; i < Math.min(kids.size(), (row + 1) * per); i++) {
        Dimension s = sizes.get(kids.get(i));
        rw += s.width + (i > row * per ? GAP : 0);
        rh = Math.max(rh, s.height);
      }
      w = Math.max(w, rw);
      h += rh + (row > 0 ? GAP : 0);
    }
    return new Dimension(w, h);
  }

  private static void place(Uml.Diagram d, Uml.Node parent, int x0, int y0, Map<Uml.Node, Dimension> sizes, Map<Uml.Node, Rectangle> out) {
    List<Uml.Node> kids = children(d, parent);
    int per = Math.max(1, (int) Math.ceil(Math.sqrt(kids.size())));
    int y = y0;
    for (int row = 0; row * per < kids.size(); row++) {
      int x = x0, rh = 0;
      for (int i = row * per; i < Math.min(kids.size(), (row + 1) * per); i++) {
        Uml.Node k = kids.get(i);
        Dimension s = sizes.get(k);
        out.put(k, new Rectangle(x, y, s.width, s.height));
        if (k.isContainer()) place(d, k, x + PAD, y + TAB, sizes, out);
        x += s.width + GAP;
        rh = Math.max(rh, s.height);
      }
      y += rh + GAP;
    }
  }

  // ------------------------------------------------------------------ use case diagrams

  /**
   * Actors in a column on the left (actors that only receive arrows from use cases go on the right),
   * systems and loose use cases in the middle, use cases ordered so lines from the actors cross as little as possible.
   */
  static Map<Uml.Node, Rectangle> useCase(Uml.Diagram d) {
    Map<Uml.Node, Rectangle> out = new LinkedHashMap<Uml.Node, Rectangle>();
    final List<Uml.Node> left = new ArrayList<Uml.Node>(), right = new ArrayList<Uml.Node>();
    List<Uml.Node> middle = new ArrayList<Uml.Node>(), notes = new ArrayList<Uml.Node>();
    for (Uml.Node n : d.nodes) {
      if (Uml.ACTOR.equals(n.kind) && n.parent == null) (onRight(d, n) ? right : left).add(n);
      else if (Uml.NOTE.equals(n.kind)) notes.add(n);
      else if (n.parent == null) middle.add(n);
    }
    groupGeneralizations(d, left);
    groupGeneralizations(d, right);
    final Map<Uml.Node, Double> rank = new HashMap<Uml.Node, Double>();
    for (Uml.Node n : d.nodes) rank.put(n, Double.valueOf(rankOf(d, n, left, right)));

    int actorW = 0;
    for (Uml.Node a : left) actorW = Math.max(actorW, size(a).width);
    int midX = 40 + (left.isEmpty() ? 0 : actorW + 110);

    // middle column
    Comparator<Uml.Node> byRank = new Comparator<Uml.Node>() {
      public int compare(Uml.Node a, Uml.Node b) { return Double.compare(rank.get(a).doubleValue(), rank.get(b).doubleValue()); }
    };
    Collections.sort(middle, byRank);
    int y = 40, midW = 0;
    for (Uml.Node n : middle) {
      Rectangle r = block(d, n, midX, y, byRank, out);
      y = r.y + r.height + 50;
      midW = Math.max(midW, r.width);
    }
    int midH = Math.max(1, y - 50 - 40);

    column(left, 40, midH, out);
    column(right, midX + midW + 110, midH, out);
    int nx = midX + midW + (right.isEmpty() ? 80 : 110 + 150);
    int ny = 40;
    for (Uml.Node n : notes) {
      Dimension s = size(n);
      out.put(n, new Rectangle(nx, ny, s.width, s.height));
      ny += s.height + 30;
    }
    return out;
  }

  /** Places a system (with its use cases stacked inside) or a loose use case; returns its rectangle. */
  private static Rectangle block(Uml.Diagram d, Uml.Node n, int x, int y, Comparator<Uml.Node> byRank, Map<Uml.Node, Rectangle> out) {
    if (!n.isContainer()) {
      Dimension s = size(n);
      Rectangle r = new Rectangle(x, y, s.width, s.height);
      out.put(n, r);
      return r;
    }
    List<Uml.Node> kids = children(d, n);
    List<Uml.Node> inner = new ArrayList<Uml.Node>();
    for (Uml.Node k : kids) if (!Uml.NOTE.equals(k.kind)) inner.add(k);
    Collections.sort(inner, byRank);
    // use cases that are only reached through include/extend go in a column of their own, to the right
    List<Uml.Node> main = new ArrayList<Uml.Node>(), side = new ArrayList<Uml.Node>();
    for (Uml.Node k : inner) (secondary(d, k) ? side : main).add(k);
    if (main.isEmpty()) { main = side; side = new ArrayList<Uml.Node>(); }
    List<List<Uml.Node>> columns = new ArrayList<List<Uml.Node>>();
    int split = main.size() > 7 && side.isEmpty() ? (main.size() + 1) / 2 : main.size();
    columns.add(main.subList(0, split));
    if (split < main.size()) columns.add(main.subList(split, main.size()));
    if (!side.isEmpty()) columns.add(side);
    int colW = 0;
    for (Uml.Node k : inner) if (!k.isContainer()) colW = Math.max(colW, size(k).width);
    int cy = y + TAB, maxY = cy, maxX = x + PAD;
    for (int c = 0; c < columns.size(); c++) {
      cy = y + TAB;
      int cx = x + PAD + c * (colW + 90);
      List<Uml.Node> col = columns.get(c);
      if (c > 0 && col == side) cy += 40; // offset so lines from the first column run diagonally, not along a row
      for (int i = 0; i < col.size(); i++) {
        Uml.Node k = col.get(i);
        Rectangle r;
        if (k.isContainer()) r = block(d, k, cx, cy, byRank, out);
        else {
          Dimension s = size(k);
          r = new Rectangle(cx + (colW - s.width) / 2, cy, s.width, s.height);
          out.put(k, r);
        }
        cy = r.y + r.height + 35;
        maxX = Math.max(maxX, r.x + r.width);
      }
      maxY = Math.max(maxY, cy - 35);
    }
    for (Uml.Node k : kids) if (Uml.NOTE.equals(k.kind)) { // notes declared inside a system go at its bottom
      Dimension s = size(k);
      out.put(k, new Rectangle(x + PAD, maxY + 20, s.width, s.height));
      maxY += 20 + s.height;
      maxX = Math.max(maxX, x + PAD + s.width);
    }
    Rectangle r = new Rectangle(x, y, Math.max(200, maxX - x + PAD), Math.max(120, maxY - y + PAD));
    out.put(n, r);
    return r;
  }

  /** True for a use case linked only by include/extend lines (no actor uses it directly). */
  private static boolean secondary(Uml.Diagram d, Uml.Node n) {
    if (!Uml.USECASE.equals(n.kind)) return false;
    boolean viaIncludeOrExtend = false;
    for (Uml.Link k : d.links) {
      if (k.from != n && k.to != n || Uml.ANCHOR.equals(k.kind)) continue;
      if (Uml.INCLUDE.equals(k.kind) && k.to == n || Uml.EXTEND.equals(k.kind) && k.to == n) viaIncludeOrExtend = true;
      else return false;
    }
    return viaIncludeOrExtend;
  }

  /** Moves each actor that specialises another actor to just after it. */
  private static void groupGeneralizations(Uml.Diagram d, List<Uml.Node> actors) {
    for (Uml.Link k : d.links) {
      if (!Uml.GENERALIZATION.equals(k.kind) || !actors.contains(k.from) || !actors.contains(k.to)) continue;
      actors.remove(k.to);
      actors.add(actors.indexOf(k.from) + 1, k.to);
    }
  }

  /** Spreads actors evenly down the height of the middle column. */
  private static void column(List<Uml.Node> actors, int x, int height, Map<Uml.Node, Rectangle> out) {
    if (actors.isEmpty()) return;
    int slot = Math.max(130, height / actors.size());
    int total = slot * actors.size();
    int y = 40 + Math.max(0, (height - total) / 2);
    for (Uml.Node a : actors) {
      Dimension s = size(a);
      out.put(a, new Rectangle(x, y + (slot - s.height) / 2, s.width, s.height));
      y += slot;
    }
  }

  /** An actor goes on the right when every line to it comes from a use case with the arrow pointing at the actor. */
  private static boolean onRight(Uml.Diagram d, Uml.Node actor) {
    boolean any = false;
    for (Uml.Link k : d.links) {
      if (k.from != actor && k.to != actor) continue;
      if (Uml.ANCHOR.equals(k.kind)) continue;
      if (!Uml.ASSOCIATION.equals(k.kind) || k.to != actor || !k.directed) return false;
      any = true;
    }
    return any;
  }

  /** Average position of the actors linked to this use case (or to the use cases a system holds). */
  private static double rankOf(Uml.Diagram d, Uml.Node n, List<Uml.Node> left, List<Uml.Node> right) {
    double sum = 0;
    int c = 0;
    for (Uml.Link k : d.links) {
      Uml.Node other = within(k.from, n) ? k.to : within(k.to, n) ? k.from : null;
      if (other == null) continue;
      int i = left.indexOf(other);
      if (i < 0) i = right.indexOf(other);
      if (i >= 0) { sum += i; c++; }
    }
    return c == 0 ? 1e6 + d.nodes.indexOf(n) : sum / c + d.nodes.indexOf(n) * 1e-6;
  }

  private static boolean within(Uml.Node x, Uml.Node container) {
    for (Uml.Node p = x; p != null; p = p.parent) if (p == container) return true;
    return false;
  }

  // ------------------------------------------------------------------ activity and state diagrams

  static Dimension flowSize(Uml.Node n) {
    String k = n.kind;
    if (Uml.INITIAL.equals(k)) return new Dimension(20, 20);
    if (Uml.FINAL.equals(k) || Uml.FLOW_FINAL.equals(k)) return new Dimension(22, 22);
    if (Uml.DECISION.equals(k) || Uml.MERGE.equals(k) || Uml.CHOICE.equals(k)) return new Dimension(30, 30);
    if (Uml.FORK.equals(k) || Uml.JOIN.equals(k)) return new Dimension(100, 6);
    if (Uml.HISTORY.equals(k) || Uml.DEEP_HISTORY.equals(k)) return new Dimension(26, 26);
    if (Uml.NOTE.equals(k)) return size(n);
    if (Uml.STATE.equals(k)) {
      int chars = n.name.length(), lines = 0;
      if (n.text != null) for (String l : n.text.split("\n")) { chars = Math.max(chars, l.length()); lines++; }
      return new Dimension(Math.max(100, Math.min(280, chars * 7 + 30)), 40 + 16 * lines);
    }
    int w = Math.max(90, Math.min(220, n.name.length() * 7 + 30)); // action
    int lines = (int) Math.ceil(n.name.length() * 7.0 / (w - 20));
    return new Dimension(w, Math.max(40, 16 * lines + 20));
  }

  private static final int FLOW_GAP_Y = 45, FLOW_GAP_X = 50, CPAD = 25, CTAB = 35, LANE_HEAD = 40;

  /** Layered top-to-bottom layout; swimlanes become columns, composite states are laid out inside their parent. */
  static Map<Uml.Node, Rectangle> flow(Uml.Diagram d) {
    Map<Uml.Node, Rectangle> rel = new HashMap<Uml.Node, Rectangle>();
    Map<Uml.Node, Dimension> sizes = new HashMap<Uml.Node, Dimension>();
    List<Uml.Node> lanes = new ArrayList<Uml.Node>();
    for (Uml.Node n : d.nodes) if (Uml.LANE.equals(n.kind)) lanes.add(n);
    Map<Uml.Node, Rectangle> out = new LinkedHashMap<Uml.Node, Rectangle>();
    int x0 = 40, y0 = 40;
    Dimension all;
    if (lanes.isEmpty()) {
      all = level(d, null, sizes, rel);
      for (Map.Entry<Uml.Node, Rectangle> e : rel.entrySet()) {
        Rectangle r = e.getValue();
        out.put(e.getKey(), new Rectangle(r.x + x0, r.y + y0, r.width, r.height));
      }
      absolute(d, null, out, rel);
    } else {
      all = lanes(d, lanes, sizes, out, x0, y0);
    }
    notes(d, out, x0 + all.width + 40, y0);
    return out;
  }

  /** Positions (relative to the container's content area) of the parent's children; returns the area's size. */
  private static Dimension level(Uml.Diagram d, Uml.Node parent, Map<Uml.Node, Dimension> sizes, Map<Uml.Node, Rectangle> rel) {
    List<Uml.Node> kids = new ArrayList<Uml.Node>();
    for (Uml.Node n : d.nodes) if (n.parent == parent && !Uml.NOTE.equals(n.kind) && !Uml.LANE.equals(n.kind)) kids.add(n);
    for (Uml.Node k : kids) {
      if (k.isContainer() && hasChildren(d, k)) {
        Dimension inner = level(d, k, sizes, rel);
        Dimension own = flowSize(k);
        sizes.put(k, new Dimension(Math.max(own.width, inner.width + 2 * CPAD), inner.height + CTAB + CPAD + (k.text == null ? 0 : 16 * k.text.split("\n").length)));
      } else sizes.put(k, flowSize(k));
    }
    List<List<Uml.Node>> layers = layers(d, kids, parent);
    return place(d, layers, sizes, rel, parent, null);
  }

  private static boolean hasChildren(Uml.Diagram d, Uml.Node p) {
    for (Uml.Node n : d.nodes) if (n.parent == p && !Uml.NOTE.equals(n.kind)) return true;
    return false;
  }

  /** The child of this container that holds the node (or the node itself), or null if it lies outside. */
  private static Uml.Node rep(Uml.Node n, Uml.Node container) {
    for (Uml.Node x = n; x != null; x = x.parent) if (x.parent == container) return x;
    return null;
  }

  /** Longest-path layering over the flow edges, with edges that close a loop ignored. */
  private static List<List<Uml.Node>> layers(Uml.Diagram d, final List<Uml.Node> nodes, Uml.Node container) {
    Map<Uml.Node, List<Uml.Node>> succ = new LinkedHashMap<Uml.Node, List<Uml.Node>>();
    for (Uml.Node n : nodes) succ.put(n, new ArrayList<Uml.Node>());
    for (Uml.Link k : d.links) {
      if (Uml.ANCHOR.equals(k.kind)) continue;
      Uml.Node a = rep(k.from, container), b = rep(k.to, container);
      if (a == null || b == null || a == b || !succ.containsKey(a) || !succ.containsKey(b)) continue;
      if (!succ.get(a).contains(b)) succ.get(a).add(b);
    }
    // depth-first from the start nodes, then from anything not reached, dropping back edges
    List<Uml.Node> roots = new ArrayList<Uml.Node>();
    for (Uml.Node n : nodes) if (Uml.INITIAL.equals(n.kind)) roots.add(n);
    for (Uml.Node n : nodes) if (!roots.contains(n)) roots.add(n);
    Map<Uml.Node, Integer> state = new HashMap<Uml.Node, Integer>();
    Map<Uml.Node, List<Uml.Node>> fwd = new LinkedHashMap<Uml.Node, List<Uml.Node>>();
    for (Uml.Node n : nodes) fwd.put(n, new ArrayList<Uml.Node>());
    for (Uml.Node r : roots) dfs(r, succ, state, fwd);
    Map<Uml.Node, Integer> layer = new HashMap<Uml.Node, Integer>();
    Map<Uml.Node, Integer> indeg = new HashMap<Uml.Node, Integer>();
    for (Uml.Node n : nodes) indeg.put(n, Integer.valueOf(0));
    for (List<Uml.Node> l : fwd.values()) for (Uml.Node t : l) indeg.put(t, Integer.valueOf(indeg.get(t).intValue() + 1));
    List<Uml.Node> queue = new ArrayList<Uml.Node>();
    for (Uml.Node n : nodes) if (indeg.get(n).intValue() == 0) { queue.add(n); layer.put(n, Integer.valueOf(0)); }
    for (int qi = 0; qi < queue.size(); qi++) {
      Uml.Node n = queue.get(qi);
      for (Uml.Node t : fwd.get(n)) {
        int l = layer.get(n).intValue() + 1;
        if (layer.get(t) == null || layer.get(t).intValue() < l) layer.put(t, Integer.valueOf(l));
        indeg.put(t, Integer.valueOf(indeg.get(t).intValue() - 1));
        if (indeg.get(t).intValue() == 0) queue.add(t);
      }
    }
    List<List<Uml.Node>> out = new ArrayList<List<Uml.Node>>();
    for (Uml.Node n : nodes) {
      int l = layer.get(n) == null ? 0 : layer.get(n).intValue();
      while (out.size() <= l) out.add(new ArrayList<Uml.Node>());
      out.get(l).add(n);
    }
    return out;
  }

  private static void dfs(Uml.Node n, Map<Uml.Node, List<Uml.Node>> succ, Map<Uml.Node, Integer> state, Map<Uml.Node, List<Uml.Node>> fwd) {
    if (state.get(n) != null) return;
    state.put(n, Integer.valueOf(1));
    for (Uml.Node t : succ.get(n)) {
      Integer s = state.get(t);
      if (s != null && s.intValue() == 1) continue; // back edge: closes a loop
      fwd.get(n).add(t);
      dfs(t, succ, state, fwd);
    }
    state.put(n, Integer.valueOf(2));
  }

  /** Places layers top to bottom; each node goes under the average of its predecessors where there is room. */
  private static Dimension place(Uml.Diagram d, List<List<Uml.Node>> layers, Map<Uml.Node, Dimension> sizes, Map<Uml.Node, Rectangle> rel,
      Uml.Node container, Map<Uml.Node, Integer> laneOf) {
    Map<Uml.Node, Double> cx = new HashMap<Uml.Node, Double>();
    int y = 0, maxW = 0;
    for (List<Uml.Node> layer : layers) {
      int w = 0;
      for (Uml.Node n : layer) w += sizes.get(n).width;
      w += FLOW_GAP_X * Math.max(0, layer.size() - 1);
      maxW = Math.max(maxW, w);
    }
    for (int li = 0; li < layers.size(); li++) {
      List<Uml.Node> layer = layers.get(li);
      final Map<Uml.Node, Double> want = new HashMap<Uml.Node, Double>();
      int w = 0;
      for (Uml.Node n : layer) w += sizes.get(n).width;
      w += FLOW_GAP_X * Math.max(0, layer.size() - 1);
      double start = (maxW - w) / 2.0, run = start;
      for (Uml.Node n : layer) { // default: spread evenly, centred
        double def = run + sizes.get(n).width / 2.0;
        run += sizes.get(n).width + FLOW_GAP_X;
        double sum = 0;
        int c = 0;
        for (Uml.Link k : d.links) {
          if (Uml.ANCHOR.equals(k.kind)) continue;
          Uml.Node a = rep(k.from, container), b = rep(k.to, container);
          if (b == n && a != null && cx.containsKey(a)) { sum += cx.get(a).doubleValue(); c++; }
        }
        want.put(n, Double.valueOf(c > 0 && layer.size() == 1 ? sum / c : c > 0 ? (sum / c + def) / 2 : def));
      }
      List<Uml.Node> sorted = new ArrayList<Uml.Node>(layer);
      Collections.sort(sorted, new Comparator<Uml.Node>() {
        public int compare(Uml.Node a, Uml.Node b) { return Double.compare(want.get(a).doubleValue(), want.get(b).doubleValue()); }
      });
      double right = -1e9;
      int rowH = 0;
      for (Uml.Node n : sorted) {
        Dimension s = sizes.get(n);
        double left = Math.max(want.get(n).doubleValue() - s.width / 2.0, right + FLOW_GAP_X);
        rel.put(n, new Rectangle((int) Math.round(left), y, s.width, s.height));
        cx.put(n, Double.valueOf(left + s.width / 2.0));
        right = left + s.width;
        rowH = Math.max(rowH, s.height);
      }
      for (Uml.Node n : sorted) { // centre each shape vertically in its row
        Rectangle r = rel.get(n);
        r.y = y + (rowH - r.height) / 2;
      }
      y += rowH + FLOW_GAP_Y;
    }
    // shift so the leftmost shape starts at 0
    int minX = Integer.MAX_VALUE, maxX = 0;
    for (List<Uml.Node> layer : layers) for (Uml.Node n : layer) { minX = Math.min(minX, rel.get(n).x); }
    if (minX == Integer.MAX_VALUE) minX = 0;
    for (List<Uml.Node> layer : layers) for (Uml.Node n : layer) { rel.get(n).x -= minX; maxX = Math.max(maxX, rel.get(n).x + rel.get(n).width); }
    return new Dimension(maxX, Math.max(0, y - FLOW_GAP_Y));
  }

  /** Turns container-relative positions into diagram positions, top-down. */
  private static void absolute(Uml.Diagram d, Uml.Node parent, Map<Uml.Node, Rectangle> out, Map<Uml.Node, Rectangle> rel) {
    for (Uml.Node n : d.nodes) {
      if (n.parent != parent || Uml.NOTE.equals(n.kind) || !out.containsKey(n)) continue;
      Rectangle box = out.get(n);
      if (!hasChildren(d, n)) continue;
      int descH = n.text == null ? 0 : 16 * n.text.split("\n").length;
      for (Uml.Node c : d.nodes) {
        if (c.parent != n || !rel.containsKey(c)) continue;
        Rectangle r = rel.get(c);
        out.put(c, new Rectangle(box.x + CPAD + r.x, box.y + CTAB + descH + r.y, r.width, r.height));
      }
      absolute(d, n, out, rel);
    }
  }

  /** Swimlanes: one column per lane, rows shared across lanes. */
  private static Dimension lanes(Uml.Diagram d, List<Uml.Node> lanes, Map<Uml.Node, Dimension> sizes, Map<Uml.Node, Rectangle> out, int x0, int y0) {
    List<Uml.Node> kids = new ArrayList<Uml.Node>();
    for (Uml.Node n : d.nodes) if (n.parent == null && !Uml.NOTE.equals(n.kind) && !Uml.LANE.equals(n.kind)) { kids.add(n); sizes.put(n, flowSize(n)); }
    List<List<Uml.Node>> layers = layers(d, kids, null);
    Map<String, Integer> laneIdx = new HashMap<String, Integer>();
    for (int i = 0; i < lanes.size(); i++) laneIdx.put(lanes.get(i).name, Integer.valueOf(i));
    int[] laneW = new int[lanes.size()];
    for (int i = 0; i < laneW.length; i++) laneW[i] = Math.max(170, lanes.get(i).name.length() * 8 + 40);
    for (List<Uml.Node> layer : layers) {
      int[] w = new int[lanes.size()];
      for (Uml.Node n : layer) {
        int li = n.lane == null || laneIdx.get(n.lane) == null ? 0 : laneIdx.get(n.lane).intValue();
        w[li] += sizes.get(n).width + FLOW_GAP_X;
      }
      for (int i = 0; i < w.length; i++) laneW[i] = Math.max(laneW[i], w[i] + 2 * CPAD);
    }
    int[] laneX = new int[lanes.size()];
    for (int i = 0, x = x0; i < lanes.size(); i++) { laneX[i] = x; x += laneW[i]; }
    int y = y0 + LANE_HEAD + 20;
    for (List<Uml.Node> layer : layers) {
      int rowH = 0;
      for (int li = 0; li < lanes.size(); li++) {
        List<Uml.Node> here = new ArrayList<Uml.Node>();
        for (Uml.Node n : layer) {
          int idx = n.lane == null || laneIdx.get(n.lane) == null ? 0 : laneIdx.get(n.lane).intValue();
          if (idx == li) here.add(n);
        }
        int w = 0;
        for (Uml.Node n : here) w += sizes.get(n).width;
        w += FLOW_GAP_X * Math.max(0, here.size() - 1);
        int x = laneX[li] + (laneW[li] - w) / 2;
        for (Uml.Node n : here) {
          Dimension s = sizes.get(n);
          out.put(n, new Rectangle(x, y, s.width, s.height));
          x += s.width + FLOW_GAP_X;
          rowH = Math.max(rowH, s.height);
        }
      }
      for (Uml.Node n : layer) { Rectangle r = out.get(n); r.y = y + (rowH - r.height) / 2; }
      y += rowH + FLOW_GAP_Y;
    }
    int total = 0;
    for (int w : laneW) total += w;
    int h = y - FLOW_GAP_Y + 30 - y0;
    for (int i = 0; i < lanes.size(); i++) out.put(lanes.get(i), new Rectangle(laneX[i], y0, laneW[i], h));
    return new Dimension(total, h);
  }

  /** Notes go to the right of the shape they are attached to, or in a column on the right. */
  private static void notes(Uml.Diagram d, Map<Uml.Node, Rectangle> out, int freeX, int y) {
    for (Uml.Node n : d.nodes) {
      if (!Uml.NOTE.equals(n.kind)) continue;
      Dimension s = size(n);
      Rectangle target = null;
      for (Uml.Link k : d.links) if (Uml.ANCHOR.equals(k.kind) && k.from == n && out.containsKey(k.to)) target = out.get(k.to);
      Rectangle r = target != null ? new Rectangle(target.x + target.width + 40, target.y - 5, s.width, s.height) : new Rectangle(freeX, y, s.width, s.height);
      for (int i = 0; i < 50 && overlaps(r, out.values()); i++) r.y += 20;
      out.put(n, r);
      if (target == null) y += s.height + 20;
    }
  }

  private static boolean overlaps(Rectangle r, java.util.Collection<Rectangle> others) {
    for (Rectangle o : others) if (o != r && r.intersects(o) && !o.contains(r) && !r.contains(o)) return true;
    return false;
  }
}
