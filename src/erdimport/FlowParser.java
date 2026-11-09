package erdimport;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads PlantUML activity diagrams (the current syntax) and state diagrams into {@link Uml.Diagram}s. */
final class FlowParser {
  private FlowParser() {}

  // ------------------------------------------------------------------ shared line handling

  /** Lines with comments, skinparams, titles and the like taken out; the title is stored in the diagram. */
  private static List<String> lines(Uml.Diagram d, String text) {
    List<String> out = new ArrayList<String>();
    boolean inBlockComment = false;
    String skipUntil = null;
    int braceSkip = 0;
    for (String raw : text.split("\n", -1)) {
      String line = raw.trim();
      if (inBlockComment) { if (line.contains("'/")) inBlockComment = false; continue; }
      if (line.startsWith("/'")) { inBlockComment = !line.substring(2).contains("'/"); continue; }
      String l = line.toLowerCase(Locale.ROOT);
      if (l.startsWith("@startuml")) { String n = line.substring(9).trim(); if (n.length() > 0 && !n.startsWith("(")) d.title = n; continue; }
      if (l.startsWith("@enduml")) break;
      if (skipUntil != null) { if (l.matches(skipUntil)) skipUntil = null; continue; }
      if (braceSkip > 0) { if (line.contains("{")) braceSkip++; if (line.contains("}")) braceSkip--; continue; }
      if (line.length() == 0 || line.startsWith("'")) continue;
      if (l.startsWith("title ")) { d.title = line.substring(6).trim(); continue; }
      if (l.equals("title")) { skipUntil = "^end\\s*title.*"; continue; }
      if (l.startsWith("legend") || l.equals("header") || l.equals("footer") || l.equals("caption")) { skipUntil = "^end\\s*(legend|header|footer|caption).*"; continue; }
      if (l.startsWith("skinparam") || l.startsWith("!") || l.startsWith("hide") || l.startsWith("show ") || l.startsWith("scale")
          || l.startsWith("header ") || l.startsWith("footer ") || l.startsWith("caption ") || l.startsWith("left to right")
          || l.startsWith("top to bottom") || l.startsWith("allowmixing") || l.startsWith("set ")) {
        if (line.endsWith("{")) braceSkip = 1;
        continue;
      }
      out.add(line);
    }
    return out;
  }

  private static void finish(Uml.Diagram d, String fallback) {
    if (d.title == null || d.title.length() == 0) d.title = fallback;
    d.title = d.title.replaceAll("<[^>]+>", "").replace("\\n", " ").trim();
  }

  /** Plain text: line breaks, HTML-like tags, Creole (**bold**, //italic//, __underline__ ...) and <&icons> removed. */
  static String text(String s) {
    s = s.replace("\\n", " ").replaceAll("<&[\\w-]+>", "").replaceAll("</?[a-zA-Z][^<>]*>", "");
    s = s.replaceAll("\\*\\*(.+?)\\*\\*", "$1").replaceAll("//(.+?)//", "$1").replaceAll("__(.+?)__", "$1")
        .replaceAll("\"\"(.+?)\"\"", "$1").replaceAll("~~(.+?)~~", "$1").replaceAll("--(.+?)--", "$1");
    return s.replaceAll("\\s+", " ").trim();
  }

  // ------------------------------------------------------------------ activity diagrams

  /** An open if / while / repeat / fork / switch. */
  private static final class Frame {
    final String type;
    Uml.Node node, last; // decision, fork or repeat start; last decision of an if/elseif chain
    final List<Object[]> ends = new ArrayList<Object[]>(); // {node, label} reaching the end of a branch
    boolean sawElse;
    String label;
    Frame(String type, Uml.Node node) { this.type = type; this.node = node; this.last = node; }
  }

  private static final Pattern IF = Pattern.compile("^if\\s*\\((.*?)\\)\\s*(?:then\\s*(?:\\((.*?)\\))?)?\\s*$", Pattern.CASE_INSENSITIVE);
  private static final Pattern ELSEIF = Pattern.compile("^else\\s*if\\s*\\((.*?)\\)\\s*(?:then\\s*(?:\\((.*?)\\))?)?\\s*$", Pattern.CASE_INSENSITIVE);
  private static final Pattern ELSE = Pattern.compile("^else\\s*(?:\\((.*?)\\))?\\s*$", Pattern.CASE_INSENSITIVE);
  private static final Pattern WHILE = Pattern.compile("^while\\s*\\((.*?)\\)\\s*(?:is\\s*\\((.*?)\\))?\\s*$", Pattern.CASE_INSENSITIVE);
  private static final Pattern ENDWHILE = Pattern.compile("^end\\s*while\\s*(?:\\((.*?)\\))?\\s*$", Pattern.CASE_INSENSITIVE);
  private static final Pattern REPEAT_WHILE = Pattern.compile("^repeat\\s*while\\s*(?:\\((.*?)\\))?\\s*(?:is\\s*\\((.*?)\\))?\\s*(?:not\\s*\\((.*?)\\))?\\s*$", Pattern.CASE_INSENSITIVE);
  private static final Pattern SWITCH = Pattern.compile("^switch\\s*\\((.*?)\\)\\s*$", Pattern.CASE_INSENSITIVE);
  private static final Pattern CASE = Pattern.compile("^case\\s*\\((.*?)\\)\\s*$", Pattern.CASE_INSENSITIVE);
  private static final Pattern LANE = Pattern.compile("^\\|(?:[^|]*\\|)?([^|]+)\\|\\s*$");
  private static final Pattern ARROW_LABEL = Pattern.compile("^-+(?:\\[[^\\]]*\\])?-*>\\s*(.*?);?\\s*$");
  private static final Pattern NOTE = Pattern.compile("^(?:floating\\s+)?note\\s+(left|right|top|bottom)?\\s*(?::\\s*(.*))?$", Pattern.CASE_INSENSITIVE);

  private static final String LNODE = "(\\(\\*(?:top)?\\)|===+[^=]+===+|\"[^\"]+\"(?:\\s+as\\s+\\w+)?|<>\\s*\\w+|[\\w.$]+)";
  private static final Pattern LEGACY = Pattern.compile(
      "^" + LNODE + "?\\s*-+(?:up|down|left|right|u|d|l|r)?-*>\\s*(?:\\[([^\\]]*)\\])?\\s*" + LNODE + "\\s*$", Pattern.CASE_INSENSITIVE);

  /** The old activity syntax:  (*) --> "Step" -->[label] "Next" --> (*) */
  private static Uml.Diagram legacy(Uml.Diagram d, List<String> ls, String fallback) {
    Uml.Node last = null;
    for (String line : ls) {
      Matcher m = LEGACY.matcher(line);
      if (!m.matches()) { d.warnings.add("line not understood and skipped (old activity syntax): " + line); continue; }
      Uml.Node from = m.group(1) == null ? last : legacyNode(d, m.group(1), true);
      Uml.Node to = legacyNode(d, m.group(3), false);
      if (from != null) d.link(Uml.CONTROL_FLOW, from, to, m.group(2) == null || m.group(2).trim().length() == 0 ? null : text(m.group(2)));
      last = to;
    }
    finish(d, fallback);
    return d;
  }

  private static Uml.Node legacyNode(Uml.Diagram d, String raw, boolean source) {
    raw = raw.trim();
    if (raw.startsWith("(*")) return source ? d.keyed(Uml.INITIAL, "", "(*)start") : d.keyed(Uml.FINAL, "", "(*)end");
    if (raw.startsWith("===")) return d.keyed(Uml.FORK, "", "sync\u0000" + raw.replace("=", "").trim());
    if (raw.startsWith("<>")) return d.keyed(Uml.DECISION, "", "dec\u0000" + raw.substring(2).trim());
    Matcher as = Pattern.compile("^\"([^\"]+)\"\\s+as\\s+(\\w+)$").matcher(raw);
    if (as.matches()) {
      Uml.Node n = d.keyed(Uml.ACTION, text(as.group(1)), "act\u0000" + as.group(2));
      d.byKey.put("act\u0000" + as.group(1), n);
      return n;
    }
    String name = PumlParser.unquote(raw);
    return d.keyed(Uml.ACTION, text(name), "act\u0000" + name);
  }

  static Uml.Diagram activity(UmlParser.Block block, String fallback) {
    Uml.Diagram d = new Uml.Diagram(Uml.Kind.ACTIVITY);
    List<String> ls = lines(d, block.text);
    for (String line : ls) if (line.contains("(*)") || line.contains("(*top)")) return legacy(d, ls, fallback);
    List<Object[]> tails = new ArrayList<Object[]>(); // {node, label}: where the next step is joined from
    List<Frame> stack = new ArrayList<Frame>();
    String pending = null; // label from a "-> text;" line
    String lane = null;
    Uml.Node last = null;
    boolean started = false;

    for (int i = 0; i < ls.size(); i++) {
      String line = ls.get(i);
      String l = line.toLowerCase(Locale.ROOT);
      Matcher m;

      // multi-line action  :text ... ;
      if (line.startsWith(":")) {
        StringBuilder b = new StringBuilder(line.substring(1));
        while (!endsAction(b.toString()) && i + 1 < ls.size()) b.append(' ').append(ls.get(++i));
        String t = b.toString().trim();
        t = t.substring(0, t.length() - 1).trim(); // ; | < > / ] }
        Uml.Node a = d.fresh(Uml.ACTION, text(t).length() == 0 ? "Action" : text(t));
        a.lane = lane;
        if (!started && tails.isEmpty() && d.nodes.size() == 1) started = true; // diagram without "start"
        connect(d, tails, a, pending); pending = null;
        tails.clear(); tails.add(new Object[] {a, null});
        last = a;
        continue;
      }
      if ((m = LANE.matcher(line)).matches()) {
        lane = text(m.group(1));
        Uml.Node ln = d.node("lane\u0000" + lane);
        if (ln == null) ln = d.keyed(Uml.LANE, lane, "lane\u0000" + lane);
        continue;
      }
      if (l.equals("start")) {
        Uml.Node s = d.fresh(Uml.INITIAL, "");
        s.lane = lane;
        connect(d, tails, s, pending); pending = null;
        tails.clear(); tails.add(new Object[] {s, null});
        started = true; last = s;
        continue;
      }
      if (l.equals("stop") || l.equals("end") || l.equals("kill") || l.equals("detach")) {
        if (!tails.isEmpty() && !l.equals("detach")) {
          Uml.Node f = d.fresh(l.equals("kill") ? Uml.FLOW_FINAL : Uml.FINAL, "");
          f.lane = lane;
          connect(d, tails, f, pending);
          last = f;
        }
        pending = null;
        tails.clear();
        continue;
      }
      if ((m = IF.matcher(line)).matches()) {
        Uml.Node dec = d.fresh(Uml.DECISION, text(m.group(1)));
        dec.lane = lane;
        connect(d, tails, dec, pending); pending = null;
        Frame f = new Frame("if", dec);
        stack.add(f);
        tails.clear(); tails.add(new Object[] {dec, m.group(2) == null ? null : text(m.group(2))});
        last = dec;
        continue;
      }
      if ((m = ELSEIF.matcher(line)).matches() && top(stack, "if") != null) {
        Frame f = top(stack, "if");
        f.ends.addAll(tails);
        Uml.Node dec = d.fresh(Uml.DECISION, text(m.group(1)));
        dec.lane = lane;
        d.link(Uml.CONTROL_FLOW, f.last, dec, null);
        f.last = dec;
        tails.clear(); tails.add(new Object[] {dec, m.group(2) == null ? null : text(m.group(2))});
        continue;
      }
      if ((m = ELSE.matcher(line)).matches() && top(stack, "if") != null) {
        Frame f = top(stack, "if");
        f.ends.addAll(tails);
        f.sawElse = true;
        tails.clear(); tails.add(new Object[] {f.last, m.group(1) == null ? null : text(m.group(1))});
        continue;
      }
      if (l.matches("^end\\s*if$")) {
        Frame f = pop(stack, "if");
        if (f == null) continue;
        f.ends.addAll(tails);
        if (!f.sawElse) f.ends.add(new Object[] {f.last, null});
        tails.clear();
        joinEnds(d, f.ends, tails, Uml.MERGE, lane);
        continue;
      }
      if ((m = SWITCH.matcher(line)).matches()) {
        Uml.Node dec = d.fresh(Uml.DECISION, text(m.group(1)));
        dec.lane = lane;
        connect(d, tails, dec, pending); pending = null;
        stack.add(new Frame("switch", dec));
        tails.clear();
        continue;
      }
      if ((m = CASE.matcher(line)).matches() && top(stack, "switch") != null) {
        Frame f = top(stack, "switch");
        f.ends.addAll(tails);
        tails.clear(); tails.add(new Object[] {f.node, text(m.group(1))});
        continue;
      }
      if (l.matches("^end\\s*switch$")) {
        Frame f = pop(stack, "switch");
        if (f == null) continue;
        f.ends.addAll(tails);
        tails.clear();
        joinEnds(d, f.ends, tails, Uml.MERGE, lane);
        continue;
      }
      if ((m = WHILE.matcher(line)).matches()) {
        Uml.Node dec = d.fresh(Uml.DECISION, text(m.group(1)));
        dec.lane = lane;
        connect(d, tails, dec, pending); pending = null;
        stack.add(new Frame("while", dec));
        tails.clear(); tails.add(new Object[] {dec, m.group(2) == null ? null : text(m.group(2))});
        continue;
      }
      if ((m = ENDWHILE.matcher(line)).matches()) {
        Frame f = pop(stack, "while");
        if (f == null) continue;
        connect(d, tails, f.node, pending); pending = null; // loop back
        tails.clear(); tails.add(new Object[] {f.node, m.group(1) == null ? null : text(m.group(1))});
        continue;
      }
      if (l.equals("repeat") || l.startsWith("repeat :") || l.startsWith("repeat:")) {
        Uml.Node mg = d.fresh(Uml.MERGE, "");
        mg.lane = lane;
        connect(d, tails, mg, pending); pending = null;
        stack.add(new Frame("repeat", mg));
        tails.clear(); tails.add(new Object[] {mg, null});
        if (line.contains(":")) { ls.set(i, line.substring(line.indexOf(':'))); i--; } // the action written on the same line
        continue;
      }
      if ((m = REPEAT_WHILE.matcher(line)).matches()) {
        Frame f = pop(stack, "repeat");
        if (f == null) continue;
        Uml.Node dec = d.fresh(Uml.DECISION, m.group(1) == null ? "" : text(m.group(1)));
        dec.lane = lane;
        connect(d, tails, dec, pending); pending = null;
        d.link(Uml.CONTROL_FLOW, dec, f.node, m.group(2) == null ? null : text(m.group(2)));
        tails.clear(); tails.add(new Object[] {dec, m.group(3) == null ? null : text(m.group(3))});
        continue;
      }
      if (l.startsWith("backward")) continue;
      if (l.equals("fork") || l.equals("split")) {
        Uml.Node fk = d.fresh(Uml.FORK, "");
        fk.lane = lane;
        connect(d, tails, fk, pending); pending = null;
        stack.add(new Frame(l, fk));
        tails.clear(); tails.add(new Object[] {fk, null});
        continue;
      }
      if (l.equals("fork again") || l.equals("split again")) {
        Frame f = top(stack, l.startsWith("fork") ? "fork" : "split");
        if (f == null) continue;
        f.ends.addAll(tails);
        tails.clear(); tails.add(new Object[] {f.node, null});
        continue;
      }
      if (l.matches("^end\\s*(fork|split|merge)(\\s*\\{.*\\})?$") || l.equals("fork end")) {
        Frame f = pop(stack, l.contains("split") ? "split" : "fork");
        if (f == null) continue;
        f.ends.addAll(tails);
        tails.clear();
        joinEnds(d, f.ends, tails, l.contains("merge") || f.type.equals("split") ? Uml.MERGE : Uml.JOIN, lane);
        continue;
      }
      if ((m = ARROW_LABEL.matcher(line)).matches()) {
        pending = m.group(1).length() == 0 ? null : text(m.group(1));
        continue;
      }
      if ((m = NOTE.matcher(line)).matches()) {
        Uml.Node n = d.fresh(Uml.NOTE, "note");
        if (m.group(2) != null) n.text = m.group(2).replace("\\n", "\n");
        else {
          StringBuilder b = new StringBuilder();
          while (i + 1 < ls.size() && !ls.get(i + 1).toLowerCase(Locale.ROOT).matches("^end\\s*note.*")) b.append(ls.get(++i)).append('\n');
          i++;
          n.text = b.toString().trim();
        }
        if (last != null) d.link(Uml.ANCHOR, n, last, null);
        continue;
      }
      if (l.startsWith("partition ") || l.startsWith("group ") || l.startsWith("package ") || l.startsWith("rectangle ") || l.startsWith("card ")) continue; // grouping is not imported
      if (line.equals("}") || line.equals("{")) continue;
      d.warnings.add("line not understood and skipped: " + line);
    }
    if (!stack.isEmpty()) d.warnings.add(stack.size() + " block(s) (if / while / fork ...) were not closed");
    finish(d, fallback);
    // lanes only matter when there is more than one
    if (d.count(Uml.LANE) < 2) {
      for (java.util.Iterator<Uml.Node> it = d.nodes.iterator(); it.hasNext();) if (Uml.LANE.equals(it.next().kind)) it.remove();
      for (Uml.Node n : d.nodes) n.lane = null;
    }
    return d;
  }

  private static boolean endsAction(String s) {
    s = s.trim();
    return s.endsWith(";") || s.endsWith("|") || s.endsWith("<") || s.endsWith(">") || s.endsWith("/") || s.endsWith("]") || s.endsWith("}");
  }

  private static void connect(Uml.Diagram d, List<Object[]> tails, Uml.Node to, String pending) {
    for (Object[] t : tails) {
      String label = (String) t[1];
      if (pending != null) label = label == null ? pending : label + " " + pending;
      d.link(Uml.CONTROL_FLOW, (Uml.Node) t[0], to, label);
    }
  }

  /** Brings the branch ends together in a merge or join node (or carries a single end through). */
  private static void joinEnds(Uml.Diagram d, List<Object[]> ends, List<Object[]> tails, String kind, String lane) {
    if (ends.isEmpty()) return;
    if (ends.size() == 1) { tails.add(ends.get(0)); return; }
    Uml.Node j = d.fresh(kind, "");
    j.lane = lane;
    for (Object[] e : ends) d.link(Uml.CONTROL_FLOW, (Uml.Node) e[0], j, (String) e[1]);
    tails.add(new Object[] {j, null});
  }

  private static Frame top(List<Frame> stack, String type) {
    return !stack.isEmpty() && stack.get(stack.size() - 1).type.equals(type) ? stack.get(stack.size() - 1) : null;
  }

  private static Frame pop(List<Frame> stack, String type) {
    for (int i = stack.size() - 1; i >= 0; i--) if (stack.get(i).type.equals(type)) {
      Frame f = stack.get(i);
      while (stack.size() > i) stack.remove(stack.size() - 1);
      return f;
    }
    return null;
  }

  // ------------------------------------------------------------------ state diagrams

  private static final String SNAME = "(\\[\\*\\]|\\[H\\*?\\]|\"[^\"]+\"|[\\w.$]+(?:\\[H\\*?\\])?)";
  private static final Pattern TRANS = Pattern.compile(
      "^" + SNAME + "\\s*(<?-+(?:\\[[^\\]]*\\])?(?:up|down|left|right|u|d|l|r)?(?:\\[[^\\]]*\\])?-*>?|<?\\.+(?:up|down|left|right)?\\.*>?)\\s*" + SNAME + "\\s*(?::\\s*(.*))?$");
  private static final Pattern STATE = Pattern.compile("^state\\s+(.+?)\\s*$", Pattern.CASE_INSENSITIVE);
  private static final Pattern DESC = Pattern.compile("^(\"[^\"]+\"|[\\w.$]+)\\s*:\\s*(.*)$");

  static Uml.Diagram state(UmlParser.Block block, String fallback) {
    Uml.Diagram d = new Uml.Diagram(Uml.Kind.STATE);
    List<String> ls = lines(d, block.text);
    List<Uml.Node> stack = new ArrayList<Uml.Node>();
    Uml.Node lastState = null;
    for (int i = 0; i < ls.size(); i++) {
      String line = ls.get(i);
      String l = line.toLowerCase(Locale.ROOT);
      Matcher m;
      if (line.equals("}")) { if (!stack.isEmpty()) stack.remove(stack.size() - 1); continue; }
      if (line.equals("--") || line.equals("||")) continue; // concurrent regions are drawn as one
      if ((m = NOTE.matcher(line)).matches() || l.startsWith("note ")) {
        Matcher on = Pattern.compile("^note\\s+(?:left|right|top|bottom)\\s+of\\s+(\\S+)\\s*(?::\\s*(.*))?$", Pattern.CASE_INSENSITIVE).matcher(line);
        Uml.Node target = null;
        String inline = null;
        if (on.matches()) { target = d.node(PumlParser.unquote(on.group(1))); inline = on.group(2); }
        else if (m.matches()) { target = lastState; inline = m.group(2); }
        Uml.Node n = d.fresh(Uml.NOTE, "note");
        n.parent = top(stack);
        if (inline != null) n.text = inline.replace("\\n", "\n");
        else {
          StringBuilder b = new StringBuilder();
          while (i + 1 < ls.size() && !ls.get(i + 1).toLowerCase(Locale.ROOT).matches("^end\\s*note.*")) b.append(ls.get(++i)).append('\n');
          i++;
          n.text = b.toString().trim();
        }
        if (target != null) d.link(Uml.ANCHOR, n, target, null);
        continue;
      }
      if ((m = STATE.matcher(line)).matches()) {
        String rest = m.group(1);
        boolean body = rest.endsWith("{");
        if (body) rest = rest.substring(0, rest.length() - 1).trim();
        String desc = null;
        Matcher dm = Pattern.compile("^(.*?)\\s*:\\s*(.*)$").matcher(rest);
        if (!rest.startsWith("\"") && dm.matches() || rest.startsWith("\"") && rest.indexOf('"', 1) > 0 && rest.substring(rest.indexOf('"', 1)).contains(":")) {
          int c = rest.startsWith("\"") ? rest.indexOf(':', rest.indexOf('"', 1)) : rest.indexOf(':');
          desc = rest.substring(c + 1).trim(); rest = rest.substring(0, c).trim();
        }
        String kind = Uml.STATE;
        Matcher st = Pattern.compile("<<\\s*(\\w+\\*?)\\s*>>").matcher(rest);
        if (st.find()) {
          String s = st.group(1).toLowerCase(Locale.ROOT);
          kind = s.equals("choice") ? Uml.CHOICE : s.equals("fork") ? Uml.FORK : s.equals("join") ? Uml.JOIN
              : s.equals("end") ? Uml.FINAL : s.equals("start") ? Uml.INITIAL : s.equals("history") ? Uml.HISTORY
              : s.equals("history*") ? Uml.DEEP_HISTORY : Uml.STATE;
          rest = st.replaceAll("").trim();
        }
        rest = rest.replaceAll("(^|\\s)#[\\w;:.#/-]+", " ").trim();
        String name, alias = null;
        Matcher as = Pattern.compile("^\"([^\"]+)\"\\s+as\\s+(\\S+)$").matcher(rest);
        Matcher as2 = Pattern.compile("^(\\S+)\\s+as\\s+\"([^\"]+)\"$").matcher(rest);
        if (as.matches()) { name = as.group(1); alias = as.group(2); }
        else if (as2.matches()) { name = as2.group(2); alias = as2.group(1); }
        else name = PumlParser.unquote(rest);
        Uml.Node n = stateNode(d, alias != null ? alias : name, stack);
        n.name = text(name);
        n.kind = kind;
        if (alias != null) d.add(n.kind, n.name, alias);
        if (desc != null) describe(n, desc);
        if (body) { n.container = true; stack.add(n); }
        lastState = n;
        continue;
      }
      if ((m = TRANS.matcher(line)).matches()) {
        String a = m.group(1), arrow = m.group(2), b = m.group(3);
        boolean back = arrow.startsWith("<") && !arrow.endsWith(">");
        Uml.Node from = endpoint(d, back ? b : a, stack, !back), to = endpoint(d, back ? a : b, stack, back);
        Uml.Link k = d.link(Uml.TRANSITION, from, to, m.group(4) == null ? null : text(m.group(4)));
        if (k.label != null && k.label.length() == 0) k.label = null;
        lastState = to;
        continue;
      }
      if ((m = DESC.matcher(line)).matches()) {
        Uml.Node n = stateNode(d, PumlParser.unquote(m.group(1)), stack);
        describe(n, m.group(2));
        continue;
      }
      d.warnings.add("line not understood and skipped: " + line);
    }
    finish(d, fallback);
    return d;
  }

  private static Uml.Node top(List<Uml.Node> stack) { return stack.isEmpty() ? null : stack.get(stack.size() - 1); }

  private static Uml.Node stateNode(Uml.Diagram d, String name, List<Uml.Node> stack) {
    Uml.Node n = d.node(name);
    if (n == null) { n = d.add(Uml.STATE, text(name), name); n.parent = top(stack); }
    return n;
  }

  /** [*] is the start (on the left of an arrow) or end (on the right) of the enclosing state; [H] is its history. */
  private static Uml.Node endpoint(Uml.Diagram d, String raw, List<Uml.Node> stack, boolean isSource) {
    Uml.Node parent = top(stack);
    String scope = parent == null ? "" : parent.name;
    if (raw.equals("[*]")) {
      String key = (isSource ? "[*]start\u0000" : "[*]end\u0000") + scope;
      Uml.Node n = d.node(key);
      if (n == null) { n = d.keyed(isSource ? Uml.INITIAL : Uml.FINAL, "", key); n.parent = parent; }
      return n;
    }
    if (raw.matches(".*\\[H\\*?\\]$")) {
      boolean deep = raw.endsWith("*]");
      String owner = raw.substring(0, raw.indexOf('['));
      Uml.Node in = owner.length() > 0 ? stateNode(d, owner, stack) : parent;
      if (in != null) in.container = true;
      String key = (deep ? "[H*]\u0000" : "[H]\u0000") + (in == null ? "" : in.name);
      Uml.Node n = d.node(key);
      if (n == null) { n = d.keyed(deep ? Uml.DEEP_HISTORY : Uml.HISTORY, "", key); n.parent = in; }
      return n;
    }
    return stateNode(d, PumlParser.unquote(raw), stack);
  }

  private static void describe(Uml.Node n, String desc) {
    desc = desc.replace("\\n", "\n").trim();
    if (desc.length() == 0) return;
    n.text = n.text == null ? desc : n.text + "\n" + desc;
  }
}
