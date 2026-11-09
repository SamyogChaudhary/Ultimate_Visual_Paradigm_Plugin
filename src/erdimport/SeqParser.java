package erdimport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads PlantUML sequence diagrams: participants, messages in order, activations, fragments and notes. */
final class SeqParser {
  private SeqParser() {}

  static final class Participant {
    String name, kind; // participant, actor, boundary, control, entity, database, collections, queue
    final List<String> stereotypes = new ArrayList<String>();
    int createdAt = -1, destroyedAt = -1; // rows
    Participant(String name, String kind) { this.name = name; this.kind = kind; }
  }

  static final class Message {
    Participant from, to;
    String text;
    boolean reply, async, create, destroy, lost, found;
    int row;
    String number;
  }

  static final class Operand {
    String guard;
    int startRow, endRow;
  }

  static final class Fragment {
    String operator, label;
    int startRow, endRow, depth;
    final List<Operand> operands = new ArrayList<Operand>();
    final List<Participant> over = new ArrayList<Participant>(); // ref over A, B
  }

  static final class Note {
    String text;
    final List<Participant> over = new ArrayList<Participant>();
    String side; // left, right, over
    int row;
  }

  static final class Activation {
    Participant who;
    int startRow, endRow = -1, depth;
  }

  static final class Diagram {
    String title;
    final Map<String, Participant> byKey = new LinkedHashMap<String, Participant>();
    final List<Participant> participants = new ArrayList<Participant>();
    final List<Message> messages = new ArrayList<Message>();
    final List<Fragment> fragments = new ArrayList<Fragment>();
    final List<Note> notes = new ArrayList<Note>();
    final List<Activation> activations = new ArrayList<Activation>();
    final List<String> warnings = new ArrayList<String>();
    int rows;

    Participant get(String key) {
      Participant p = byKey.get(key);
      return p != null ? p : byKey.get(key.toLowerCase(Locale.ROOT));
    }

    Participant add(String name, String kind, String alias) {
      Participant p = get(alias != null ? alias : name);
      if (p != null) {
        if (kind != null) p.kind = kind;
        if (alias != null && !name.equals(p.name)) p.name = name;
        return p;
      }
      p = new Participant(name, kind == null ? "participant" : kind);
      participants.add(p);
      byKey.put(name, p);
      byKey.put(name.toLowerCase(Locale.ROOT), p);
      if (alias != null) { byKey.put(alias, p); byKey.put(alias.toLowerCase(Locale.ROOT), p); }
      return p;
    }
  }

  private static final String PNAME = "(\"[^\"]+\"|[\\w.$@]+|\\[|\\])";
  private static final Pattern MSG = Pattern.compile(
      "^" + PNAME + "\\s*(<<?|\\\\\\\\|//|x|o|\\(\\d*\\))?(-{1,2})(?:\\[[^\\]]*\\])?(-?)(>>?|\\\\\\\\|//|x|o|\\(\\d*\\))?\\s*" + PNAME
      + "\\s*(\\+\\+|--|\\*\\*|!!)?\\s*(?:(\\+\\+|--)\\s*)?(?::\\s*(.*))?$");
  private static final Pattern DECL = Pattern.compile(
      "^(participant|actor|boundary|control|entity|database|collections|queue)\\s+(.+?)\\s*$", Pattern.CASE_INSENSITIVE);
  private static final Pattern FRAG = Pattern.compile(
      "^(alt|opt|loop|par|break|critical|group|neg|strict|seq|assert|ignore|consider|ref)\\b\\s*(.*)$", Pattern.CASE_INSENSITIVE);
  private static final Pattern NOTE = Pattern.compile(
      "^[hr]?note\\s+(over|left|right)(?:\\s+of)?\\s*([^:]*?)\\s*(?::\\s*(.*))?$", Pattern.CASE_INSENSITIVE);

  static Diagram parse(String text, String fallback) {
    Diagram d = new Diagram();
    List<Fragment> open = new ArrayList<Fragment>();
    List<Activation> active = new ArrayList<Activation>(); // currently open activations, innermost last
    List<Message> calls = new ArrayList<Message>(); // calls waiting for a "return"
    int row = 0;
    boolean autonumber = false;
    int number = 1;
    boolean inBlockComment = false;
    String skipUntil = null;
    String[] lines = text.split("\n", -1);
    for (int i = 0; i < lines.length; i++) {
      String line = lines[i].trim();
      if (inBlockComment) { if (line.contains("'/")) inBlockComment = false; continue; }
      if (line.startsWith("/'")) { inBlockComment = !line.substring(2).contains("'/"); continue; }
      String l = line.toLowerCase(Locale.ROOT);
      if (l.startsWith("@startuml")) { String n = line.substring(9).trim(); if (n.length() > 0 && !n.startsWith("(")) d.title = n; continue; }
      if (l.startsWith("@enduml")) break;
      if (skipUntil != null) { if (l.matches(skipUntil)) skipUntil = null; continue; }
      if (line.length() == 0 || line.startsWith("'")) continue;
      if (l.startsWith("title ")) { d.title = line.substring(6).trim(); continue; }
      if (l.equals("title")) { skipUntil = "^end\\s*title.*"; continue; }
      if (l.startsWith("legend") || l.equals("header") || l.equals("footer")) { skipUntil = "^end\\s*(legend|header|footer).*"; continue; }
      if (l.startsWith("skinparam") || l.startsWith("!") || l.startsWith("hide") || l.startsWith("show") || l.startsWith("scale")
          || l.startsWith("header ") || l.startsWith("footer ") || l.startsWith("caption") || l.startsWith("box")
          || l.equals("end box") || l.startsWith("newpage") || l.startsWith("mainframe") || l.startsWith("left to right")) {
        if (line.endsWith("{")) skipUntil = "^\\}.*";
        continue;
      }
      if (l.startsWith("autonumber")) {
        autonumber = !l.contains("stop");
        Matcher an = Pattern.compile("^autonumber\\s+(\\d+)").matcher(l);
        if (an.find()) number = Integer.parseInt(an.group(1));
        continue;
      }
      if (l.matches("^==.*==$") || l.matches("^\\.\\.\\..*") || l.matches("^\\|\\|\\d*\\|\\|$") || l.equals("|||")) { row++; continue; }

      Matcher m;
      if ((m = DECL.matcher(line)).matches()) {
        declare(d, m.group(1).toLowerCase(Locale.ROOT), m.group(2));
        continue;
      }
      if ((m = Pattern.compile("^create\\s+(?:(participant|actor|boundary|control|entity|database|collections|queue)\\s+)?(.+)$", Pattern.CASE_INSENSITIVE).matcher(line)).matches()) {
        Participant p = declare(d, m.group(1) == null ? null : m.group(1).toLowerCase(Locale.ROOT), m.group(2));
        p.createdAt = -2; // the next message to it creates it
        continue;
      }
      if ((m = Pattern.compile("^(activate|deactivate|destroy)\\s+(\\S+).*$", Pattern.CASE_INSENSITIVE).matcher(line)).matches()) {
        Participant p = participant(d, m.group(2));
        String w = m.group(1).toLowerCase(Locale.ROOT);
        if (w.equals("activate")) activate(d, active, p, Math.max(0, row - 1));
        else { deactivate(active, p, row); if (w.equals("destroy")) { p.destroyedAt = row; row++; } }
        continue;
      }
      if ((m = Pattern.compile("^return\\b\\s*(.*)$", Pattern.CASE_INSENSITIVE).matcher(line)).matches()) {
        Message call = calls.isEmpty() ? null : calls.remove(calls.size() - 1);
        if (call == null) { d.warnings.add("line " + (i + 1) + ": return with no call to return from"); continue; }
        Message r = new Message();
        r.from = call.to; r.to = call.from; r.reply = true; r.text = FlowParser.text(m.group(1)); r.row = row++;
        if (autonumber) r.number = String.valueOf(number++);
        d.messages.add(r);
        deactivate(active, call.to, r.row);
        continue;
      }
      if ((m = FRAG.matcher(line)).matches()) {
        Fragment f = new Fragment();
        f.operator = m.group(1).toLowerCase(Locale.ROOT);
        f.label = FlowParser.text(m.group(2));
        f.startRow = row++;
        f.depth = open.size();
        Operand o = new Operand();
        o.guard = f.label; o.startRow = f.startRow;
        f.operands.add(o);
        if (f.operator.equals("ref")) { // ref over A, B : text   (one row, no end)
          Matcher ro = Pattern.compile("^over\\s+([^:]+?)\\s*(?::\\s*(.*))?$", Pattern.CASE_INSENSITIVE).matcher(m.group(2));
          if (ro.matches()) {
            for (String p : ro.group(1).split(",")) if (p.trim().length() > 0) f.over.add(participant(d, p.trim()));
            f.label = ro.group(2) == null ? "" : FlowParser.text(ro.group(2));
            if (ro.group(2) == null) { // multi-line ref ... end ref
              StringBuilder b = new StringBuilder();
              while (i + 1 < lines.length && !lines[i + 1].trim().toLowerCase(Locale.ROOT).matches("^end\\s*ref.*")) b.append(lines[++i].trim()).append(' ');
              i++;
              f.label = FlowParser.text(b.toString());
            }
          }
          f.endRow = row++;
          o.endRow = f.endRow;
          d.fragments.add(f);
          continue;
        }
        open.add(f);
        continue;
      }
      if (l.matches("^else\\b.*") && !open.isEmpty()) {
        Fragment f = open.get(open.size() - 1);
        Operand prev = f.operands.get(f.operands.size() - 1);
        prev.endRow = row;
        Operand o = new Operand();
        o.guard = FlowParser.text(line.substring(4));
        o.startRow = row++;
        f.operands.add(o);
        continue;
      }
      if (l.equals("end") && !open.isEmpty()) {
        Fragment f = open.remove(open.size() - 1);
        f.endRow = row++;
        f.operands.get(f.operands.size() - 1).endRow = f.endRow;
        d.fragments.add(f);
        continue;
      }
      if ((m = NOTE.matcher(line)).matches()) {
        Note n = new Note();
        n.side = m.group(1).toLowerCase(Locale.ROOT);
        for (String p : m.group(2).split(",")) if (p.trim().length() > 0) n.over.add(participant(d, p.trim()));
        if (m.group(3) != null) n.text = m.group(3).replace("\\n", "\n");
        else {
          StringBuilder b = new StringBuilder();
          while (i + 1 < lines.length && !lines[i + 1].trim().toLowerCase(Locale.ROOT).matches("^end\\s*[hr]?note.*")) b.append(lines[++i].trim()).append('\n');
          i++;
          n.text = b.toString().trim();
        }
        n.row = Math.max(0, row - 1);
        if (!n.over.isEmpty() || !d.messages.isEmpty()) d.notes.add(n);
        if (n.over.isEmpty() && !d.messages.isEmpty()) n.over.add(d.messages.get(d.messages.size() - 1).to);
        row++;
        continue;
      }
      if ((m = MSG.matcher(line)).matches()) {
        String a = m.group(1), lhead = m.group(2), b1 = m.group(3), b2 = m.group(4), rhead = m.group(5), b = m.group(6);
        if (lhead == null && rhead == null) { d.warnings.add("line " + (i + 1) + " has no arrow head and was skipped: " + line); continue; }
        boolean leftward = lhead != null && rhead == null || lhead != null && lhead.startsWith("<") && (rhead == null || !rhead.startsWith(">"));
        Message msg = new Message();
        String src = leftward ? b : a, dst = leftward ? a : b;
        msg.found = src.equals("[") || src.equals("]");
        msg.lost = dst.equals("[") || dst.equals("]") || "x".equals(leftward ? lhead : rhead);
        msg.from = msg.found ? null : participant(d, src);
        msg.to = dst.equals("[") || dst.equals("]") ? null : participant(d, dst);
        if (msg.from == null && msg.to == null) continue;
        msg.reply = (b1 + b2).length() >= 2;
        String head = leftward ? lhead : rhead;
        msg.async = head != null && (head.equals(">>") || head.equals("<<") || head.contains("\\") || head.contains("/"));
        msg.text = m.group(9) == null ? "" : FlowParser.text(m.group(9));
        msg.row = row++;
        if (msg.from == msg.to) row++; // a message to itself needs room to turn back
        if (autonumber) msg.number = String.valueOf(number++);
        String mod1 = m.group(7), mod2 = m.group(8);
        if (msg.to != null && msg.to.createdAt == -2) { msg.create = true; msg.to.createdAt = msg.row; }
        if ("**".equals(mod1)) { msg.create = true; msg.to.createdAt = msg.row; }
        d.messages.add(msg);
        for (String mod : new String[] {mod1, mod2}) {
          if ("++".equals(mod) && msg.to != null) activate(d, active, msg.to, msg.row);
          if ("--".equals(mod) && msg.from != null) deactivate(active, msg.from, msg.row);
          if ("!!".equals(mod) && msg.to != null) { msg.destroy = true; msg.to.destroyedAt = msg.row; deactivate(active, msg.to, msg.row); }
        }
        if (!msg.reply && msg.from != null && msg.to != null && msg.from != msg.to) calls.add(msg);
        else if (msg.reply) { // a reply closes the latest call between the two
          for (int c = calls.size() - 1; c >= 0; c--) if (calls.get(c).to == msg.from && calls.get(c).from == msg.to) { calls.remove(c); break; }
        }
        continue;
      }
      d.warnings.add("line " + (i + 1) + " was not understood and was skipped: " + line);
    }
    for (Fragment f : open) { f.endRow = row; f.operands.get(f.operands.size() - 1).endRow = row; d.fragments.add(f); }
    if (!open.isEmpty()) d.warnings.add(open.size() + " group(s) (alt / loop ...) were not closed with end");
    for (Activation a : active) if (a.endRow < 0) a.endRow = row;
    d.rows = row;
    if (d.title == null || d.title.length() == 0) d.title = fallback;
    d.title = d.title.replaceAll("<[^>]+>", "").replace("\\n", " ").trim();
    return d;
  }

  private static Participant declare(Diagram d, String kind, String rest) {
    List<String> stereos = new ArrayList<String>();
    Matcher st = Pattern.compile("<<\\s*([^>]*?)\\s*>>").matcher(rest);
    while (st.find()) stereos.add(st.group(1));
    rest = st.replaceAll(" ").replaceAll("\\s+order\\s+-?\\d+", " ").replaceAll("(^|\\s)#[\\w;:.#/-]+", " ").trim();
    String name, alias = null;
    Matcher m;
    if ((m = Pattern.compile("^\"([^\"]+)\"\\s+as\\s+(\\S+)$").matcher(rest)).matches()) { name = m.group(1); alias = m.group(2); }
    else if ((m = Pattern.compile("^(\\S+)\\s+as\\s+\"([^\"]+)\"$").matcher(rest)).matches()) { name = m.group(2); alias = m.group(1); }
    else if ((m = Pattern.compile("^(\\S+)\\s+as\\s+(\\S+)$").matcher(rest)).matches()) { name = m.group(1); alias = m.group(2); }
    else name = PumlParser.unquote(rest);
    Participant p = d.add(FlowParser.text(name), kind, alias);
    for (String s : stereos) if (!p.stereotypes.contains(s)) p.stereotypes.add(s);
    return p;
  }

  private static Participant participant(Diagram d, String raw) {
    String key = PumlParser.unquote(raw);
    Participant p = d.get(key);
    return p != null ? p : d.add(FlowParser.text(key), null, null);
  }

  private static void activate(Diagram d, List<Activation> active, Participant p, int row) {
    Activation a = new Activation();
    a.who = p; a.startRow = row;
    for (Activation o : active) if (o.who == p) a.depth++;
    active.add(a);
    d.activations.add(a);
  }

  private static void deactivate(List<Activation> active, Participant p, int row) {
    for (int i = active.size() - 1; i >= 0; i--) {
      if (active.get(i).who == p) { active.get(i).endRow = row; active.remove(i); return; }
    }
  }
}
