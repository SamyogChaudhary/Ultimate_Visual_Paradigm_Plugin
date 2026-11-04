package erdimport;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads PlantUML entity-relationship (Information Engineering, crow's foot) diagrams:
 * entity blocks with columns, and relationship lines such as  A ||--o{ B : label.
 * Optional hints in PlantUML comments (ignored by PlantUML itself):
 *   ' vp-fk column[, column]   after a relationship line: the child column(s) holding the FK
 *   ' vp-pos ENTITY x y [w h]  where to put the table on the Visual Paradigm diagram
 */
final class PumlParser {
  private PumlParser() {}

  private static final String NAME = "(\"[^\"]+\"|[\\w.$]+)";
  private static final Pattern REL = Pattern.compile(
      "^" + NAME + "\\s*(\\|o|\\|\\||\\}o|\\}\\|)?"
      + "([-.]+)(?:\\[[^\\]]*\\])?(?:up|down|left|right|u|d|l|r)?(?:\\[[^\\]]*\\])?([-.]*)"
      + "(o\\||\\|\\||o\\{|\\|\\{)?\\s*" + NAME + "\\s*(?::\\s*(.*))?$");
  private static final Pattern REL2 = Pattern.compile(
      "^" + NAME + "\\s+\"([^\"]+)\"\\s*([-.]+)(?:\\[[^\\]]*\\])?(?:up|down|left|right|u|d|l|r)?([-.]*)\\s*\"([^\"]+)\"\\s+" + NAME + "\\s*(?::\\s*(.*))?$");
  private static final Pattern BLOCK = Pattern.compile(
      "^(?i:entity|class|table|abstract\\s+class|abstract)\\s+(.+?)\\s*$");
  private static final Pattern SEPARATOR = Pattern.compile("^(--|==|\\.\\.|__).*");
  private static final Pattern STEREO = Pattern.compile("<<([^>]*)>>");
  private static final Pattern BRACES = Pattern.compile("\\{([^}]*)\\}");
  private static final Pattern TYPE = Pattern.compile("^([A-Za-z][A-Za-z0-9_ ]*?)\\s*(?:\\(\\s*(\\d+)\\s*(?:,\\s*(\\d+)\\s*)?\\))?\\s*$");
  private static final Pattern POS = Pattern.compile("^'\\s*vp-pos\\s+(\"[^\"]+\"|\\S+)\\s+(-?\\d+)\\s+(-?\\d+)(?:\\s+(\\d+)\\s+(\\d+))?\\s*$", Pattern.CASE_INSENSITIVE);
  private static final Pattern FKHINT = Pattern.compile("^'\\s*vp-fk\\s+(.+)$", Pattern.CASE_INSENSITIVE);

  static List<Puml.Diagram> parse(String text, String fallbackName) {
    List<Puml.Diagram> out = new ArrayList<Puml.Diagram>();
    text = text.replace("\uFEFF", "");
    String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
    boolean hasStart = text.contains("@startuml");
    Puml.Diagram cur = hasStart ? null : new Puml.Diagram();
    Puml.Entity open = null;
    Puml.Rel lastRel = null;
    String skipUntil = null;
    boolean inBlockComment = false;
    int braceSkip = 0;

    for (int i = 0; i < lines.length; i++) {
      String line = lines[i].trim();
      if (inBlockComment) { if (line.contains("'/")) inBlockComment = false; continue; }
      if (line.startsWith("/'")) { inBlockComment = !line.substring(2).contains("'/"); continue; }
      String lower = line.toLowerCase(Locale.ROOT);
      if (lower.startsWith("@startuml")) {
        cur = new Puml.Diagram();
        String n = line.substring(9).trim();
        if (n.length() > 0) cur.title = n;
        open = null; lastRel = null; skipUntil = null; braceSkip = 0;
        continue;
      }
      if (lower.startsWith("@enduml")) {
        if (cur != null) finish(cur, fallbackName, out);
        cur = null;
        continue;
      }
      if (cur == null) continue;
      if (skipUntil != null) { if (lower.matches(skipUntil)) skipUntil = null; continue; }
      if (braceSkip > 0) {
        if (line.contains("{")) braceSkip++;
        if (line.contains("}")) braceSkip--;
        continue;
      }
      if (line.startsWith("'")) { hint(cur, line, lastRel); continue; }
      if (line.length() == 0) continue;

      if (open != null) {
        if (line.startsWith("}")) { closeEntity(open); open = null; continue; }
        member(open, line);
        continue;
      }

      if (lower.contains("[hidden]") || lower.contains("[hidden,") || lower.contains(",hidden]")) continue; // layout-only line

      Matcher r2 = REL2.matcher(line);
      if (r2.matches()) {
        Puml.Rel rel = new Puml.Rel();
        rel.left = unquote(r2.group(1));
        rel.right = unquote(r2.group(6));
        rel.leftMult = umlMult(r2.group(2));
        rel.rightMult = umlMult(r2.group(5));
        rel.dashed = (r2.group(3) + r2.group(4)).contains(".");
        rel.label = r2.group(7) == null ? null : r2.group(7).replaceAll("^\"|\"$", "").trim();
        rel.line = i + 1;
        implicitEntity(cur, rel.left);
        implicitEntity(cur, rel.right);
        cur.rels.add(rel);
        lastRel = rel;
        continue;
      }
      Matcher r = REL.matcher(line);
      if (r.matches()) {
        Puml.Rel rel = new Puml.Rel();
        rel.left = unquote(r.group(1));
        rel.right = unquote(r.group(6));
        rel.dashed = (r.group(3) + r.group(4)).contains(".");
        rel.leftMult = leftMult(r.group(2));
        rel.rightMult = rightMult(r.group(5));
        rel.label = r.group(7) == null ? null : r.group(7).replaceAll("^\"|\"$", "").trim();
        rel.line = i + 1;
        if (r.group(2) == null || r.group(5) == null) {
          cur.warnings.add("line " + rel.line + ": " + line + " has no crow's foot ends; treated as one-to-many");
        }
        implicitEntity(cur, rel.left);
        implicitEntity(cur, rel.right);
        cur.rels.add(rel);
        lastRel = rel;
        continue;
      }
      if (lower.startsWith("title ")) { cur.title = line.substring(6).trim(); continue; }
      if (lower.equals("title")) { skipUntil = "^end\\s*title.*"; continue; }
      if (lower.startsWith("legend") || lower.equals("header") || lower.equals("footer") || lower.equals("caption")) {
        skipUntil = "^end\\s*(legend|header|footer|caption).*"; continue;
      }
      if (lower.startsWith("note ") && !line.contains(":")) { skipUntil = "^end\\s*note.*"; continue; }
      if (lower.startsWith("skinparam") || lower.startsWith("!") || lower.startsWith("hide") || lower.startsWith("show")
          || lower.startsWith("left to right") || lower.startsWith("top to bottom") || lower.startsWith("scale")
          || lower.startsWith("header ") || lower.startsWith("footer ") || lower.startsWith("caption ")
          || lower.startsWith("note ") || lower.startsWith("package ") || lower.startsWith("namespace ")
          || lower.startsWith("together") || lower.equals("}")) {
        if (line.endsWith("{") && !lower.startsWith("package ") && !lower.startsWith("namespace ") && !lower.startsWith("together")) braceSkip = 1;
        continue;
      }

      Matcher b = BLOCK.matcher(line);
      if (b.matches()) {
        String rest = b.group(1);
        boolean body = rest.endsWith("{");
        if (body) rest = rest.substring(0, rest.length() - 1).trim();
        if (rest.endsWith("}")) { // entity X { } on one line
          rest = rest.substring(0, rest.length() - 1).trim();
          if (rest.endsWith("{")) rest = rest.substring(0, rest.length() - 1).trim();
          body = false;
        }
        rest = STEREO.matcher(rest).replaceAll("").replaceAll("#\\S+", "").trim();
        String name, alias = null;
        Matcher m;
        if ((m = Pattern.compile("^\"([^\"]+)\"\\s+as\\s+(\\S+)$").matcher(rest)).matches()) { name = m.group(1); alias = m.group(2); }
        else if ((m = Pattern.compile("^(\\S+)\\s+as\\s+\"([^\"]+)\"$").matcher(rest)).matches()) { alias = m.group(1); name = m.group(2); }
        else if ((m = Pattern.compile("^(\\S+)\\s+as\\s+(\\S+)$").matcher(rest)).matches()) { name = m.group(1); alias = m.group(2); }
        else name = unquote(rest);
        Puml.Entity e = cur.addEntity(name.trim(), alias);
        if (body) open = e;
        lastRel = null;
        continue;
      }

      // anything else (arrows of other diagram types, notes, colours) is ignored
    }
    if (cur != null) finish(cur, fallbackName, out);
    return out;
  }

  private static void implicitEntity(Puml.Diagram d, String name) {
    if (d.entity(name) == null) d.addEntity(name, null).implicit = true;
  }

  private static void finish(Puml.Diagram d, String fallbackName, List<Puml.Diagram> out) {
    if (d.entities.isEmpty()) return;
    if (d.title == null || d.title.length() == 0) d.title = fallbackName + (out.isEmpty() ? "" : " " + (out.size() + 1));
    d.title = d.title.replaceAll("<[^>]+>", "").replace("\\n", " ").trim();
    for (Puml.Entity e : d.entities) closeEntity(e);
    resolve(d);
    out.add(d);
  }

  private static void hint(Puml.Diagram d, String line, Puml.Rel lastRel) {
    Matcher m = POS.matcher(line);
    if (m.matches()) {
      Puml.Entity e = d.entity(unquote(m.group(1)));
      if (e == null) return;
      e.x = Integer.valueOf(m.group(2));
      e.y = Integer.valueOf(m.group(3));
      if (m.group(4) != null) { e.w = Integer.valueOf(m.group(4)); e.h = Integer.valueOf(m.group(5)); }
      return;
    }
    m = FKHINT.matcher(line);
    if (m.matches() && lastRel != null) {
      for (String c : m.group(1).split("[,\\s]+")) if (c.trim().length() > 0) lastRel.fkCols.add(c.trim());
    }
  }

  private static void member(Puml.Entity e, String line) {
    if (SEPARATOR.matcher(line).matches()) { e.sawSeparator = true; return; }
    String s = line;
    Set<String> tags = new HashSet<String>();
    Matcher m = STEREO.matcher(s);
    while (m.find()) addTags(tags, m.group(1));
    s = m.replaceAll(" ");
    m = BRACES.matcher(s);
    while (m.find()) addTags(tags, m.group(1));
    s = m.replaceAll(" ");
    s = s.replaceAll("<&[^>]*>", " ").replaceAll("</?[a-zA-Z][^<>]*>", "").trim();

    boolean mandatory = false;
    while (s.length() > 0 && "*+#~-".indexOf(s.charAt(0)) >= 0) {
      if (s.charAt(0) == '*') mandatory = true;
      s = s.substring(1).trim();
    }
    Matcher pre = Pattern.compile("^(?i)(PK\\s*,\\s*FK|FK\\s*,\\s*PK|PK|FK|UK|UQ)\\s+(.*)$").matcher(s);
    if (pre.matches()) { addTags(tags, pre.group(1)); s = pre.group(2).trim(); }

    String name, type = null;
    int colon = s.indexOf(':');
    if (colon >= 0) { name = s.substring(0, colon).trim(); type = s.substring(colon + 1).trim(); }
    else {
      String[] parts = s.split("\\s+", 2);
      name = parts[0];
      type = parts.length > 1 ? parts[1].trim() : null;
    }
    name = unquote(name).replace("`", "").replace("[", "").replace("]", "").trim();
    if (name.length() == 0 || name.contains("(")) return; // empty, or a method of a class-style block

    if (type != null) {
      String t = " " + type + " ";
      if (t.matches("(?i).*\\s(primary\\s+key|pk)\\s.*")) tags.add("pk");
      if (t.matches("(?i).*\\s(foreign\\s+key|fk|references\\s+\\S+)\\s.*")) tags.add("fk");
      if (t.matches("(?i).*\\snot\\s+null\\s.*")) tags.add("nn");
      if (t.matches("(?i).*\\s(unique|uq|uk)\\s.*")) tags.add("uq");
      type = t.replaceAll("(?i)\\s(primary\\s+key|foreign\\s+key|references\\s+\\S+(\\s*\\([^)]*\\))?|not\\s+null|null|unique|pk|fk|uq|uk|auto_increment|identity(\\s*\\([^)]*\\))?|generated)(?=\\s)", " ").trim();
    }

    Puml.Column c = new Puml.Column();
    c.name = name;
    c.inKeySection = !e.sawSeparator;
    c.pk = tags.contains("pk") || tags.contains("primary") || tags.contains("key") || tags.contains("id");
    c.fk = tags.contains("fk") || tags.contains("foreign");
    c.unique = tags.contains("uq") || tags.contains("uk") || tags.contains("unique");
    c.mandatory = mandatory || c.pk || tags.contains("nn") || tags.contains("notnull") || tags.contains("mandatory");
    c.explicitKeyTag = c.pk || c.fk;
    if (type != null && type.length() > 0) {
      c.typeText = type;
      Matcher tm = TYPE.matcher(type);
      if (tm.matches()) {
        c.type = tm.group(1).trim().toLowerCase(Locale.ROOT);
        c.length = tm.group(2) == null ? 0 : Integer.parseInt(tm.group(2));
        c.scale = tm.group(3) == null ? 0 : Integer.parseInt(tm.group(3));
      } else {
        c.type = type.toLowerCase(Locale.ROOT);
      }
    }
    if (e.col(name) == null) e.cols.add(c);
  }

  private static void addTags(Set<String> tags, String text) {
    String t = text.toLowerCase(Locale.ROOT).replace("primary key", "pk").replace("foreign key", "fk").replace("not null", "notnull");
    for (String s : t.split("[,;\\s]+")) if (s.length() > 0) tags.add(s);
  }

  private static void closeEntity(Puml.Entity e) {
    if (!e.sawSeparator) return;
    boolean anyPk = false;
    for (Puml.Column c : e.cols) anyPk |= c.pk;
    for (Puml.Column c : e.cols) {
      // PlantUML convention: columns above the "--" line are the key, unless tagged otherwise
      if (!anyPk && c.inKeySection && !c.explicitKeyTag) { c.pk = true; c.mandatory = true; }
    }
  }

  private static String leftMult(String t) {
    if (t == null) return Puml.ONE;
    if (t.equals("||")) return Puml.ONE;
    if (t.equals("|o")) return Puml.ZERO_TO_ONE;
    if (t.equals("}o")) return Puml.ZERO_TO_MANY;
    return Puml.ONE_TO_MANY; // }|
  }

  private static String rightMult(String t) {
    if (t == null) return Puml.ZERO_TO_MANY;
    if (t.equals("||")) return Puml.ONE;
    if (t.equals("o|")) return Puml.ZERO_TO_ONE;
    if (t.equals("o{")) return Puml.ZERO_TO_MANY;
    return Puml.ONE_TO_MANY; // |{
  }

  /** UML multiplicity text such as 1, 0..1, *, 0..*, 1..* */
  private static String umlMult(String m) {
    String t = m.replace(" ", "").toLowerCase(Locale.ROOT);
    if (t.equals("1") || t.equals("1..1")) return Puml.ONE;
    if (t.equals("0..1")) return Puml.ZERO_TO_ONE;
    if (t.startsWith("1..") || t.equals("1..n")) return Puml.ONE_TO_MANY;
    return Puml.ZERO_TO_MANY; // *, 0..*, n, 0..n, many
  }

  static String unquote(String s) {
    s = s.trim();
    if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) s = s.substring(1, s.length() - 1);
    return s.trim();
  }

  // ------------------------------------------------------------------ parent/child and FK columns

  static void resolve(Puml.Diagram d) {
    java.util.Map<Puml.Entity, Set<String>> used = new java.util.HashMap<Puml.Entity, Set<String>>();
    List<Puml.Rel> rels = new ArrayList<Puml.Rel>(d.rels);
    for (Puml.Rel r : rels) {
      Puml.Entity L = d.entity(r.left), Rt = d.entity(r.right);
      if (Puml.isMany(r.leftMult) && Puml.isMany(r.rightMult)) {
        junction(d, r, L, Rt, used);
        continue;
      }
      Puml.Link k = new Puml.Link();
      k.label = r.label;
      boolean leftIsParent;
      if (!Puml.isMany(r.leftMult) && Puml.isMany(r.rightMult)) leftIsParent = true;
      else if (Puml.isMany(r.leftMult)) leftIsParent = false;
      else leftIsParent = oneToOneLeftIsParent(r, L, Rt);
      k.parent = leftIsParent ? L : Rt;
      k.child = leftIsParent ? Rt : L;
      k.fromMult = leftIsParent ? r.leftMult : r.rightMult;
      k.toMult = leftIsParent ? r.rightMult : r.leftMult;
      if (Puml.isMany(k.fromMult)) k.fromMult = Puml.ONE; // cannot happen, kept safe
      pairColumns(d, k, r.fkCols, used);
      d.links.add(k);
    }
  }

  private static boolean oneToOneLeftIsParent(Puml.Rel r, Puml.Entity L, Puml.Entity Rt) {
    for (String c : r.fkCols) {
      if (Rt.col(c) != null && L.col(c) == null) return true;
      if (L.col(c) != null && Rt.col(c) == null) return false;
    }
    if (findFk(Rt, L, new HashSet<String>()) != null) return true;
    if (findFk(L, Rt, new HashSet<String>()) != null) return false;
    return true;
  }

  /** A child column tagged FK that matches the parent's primary key, if any. */
  private static Puml.Column findFk(Puml.Entity child, Puml.Entity parent, Set<String> used) {
    List<Puml.Column> pks = parent.pks();
    if (pks.isEmpty()) return null;
    String pk = pks.get(0).name.toLowerCase(Locale.ROOT);
    for (Puml.Column c : child.cols) if (c.fk && !used.contains(c.name) && c.name.equalsIgnoreCase(pk)) return c;
    for (Puml.Column c : child.cols) if (c.fk && !used.contains(c.name) && c.name.toLowerCase(Locale.ROOT).contains(pk)) return c;
    return null;
  }

  private static void pairColumns(Puml.Diagram d, Puml.Link k, List<String> hinted, java.util.Map<Puml.Entity, Set<String>> usedMap) {
    Set<String> used = usedMap.get(k.child);
    if (used == null) { used = new HashSet<String>(); usedMap.put(k.child, used); }
    List<Puml.Column> pks = k.parent.pks();
    if (pks.isEmpty()) {
      d.warnings.add(k.parent.name + " has no primary key, so the line to " + k.child.name + " has no FK column");
      return;
    }
    boolean identifying = true;
    for (int i = 0; i < pks.size(); i++) {
      Puml.Column pc = pks.get(i);
      Puml.Column cc = null;
      if (i < hinted.size()) cc = k.child.col(hinted.get(i));
      if (cc == null && i < hinted.size()) {
        // the hint names a column that is not listed: create it with that name
        cc = pc.copyAs(hinted.get(i)); cc.fk = true; cc.created = true; cc.mandatory = Puml.ONE.equals(k.fromMult);
        k.child.cols.add(cc);
      }
      boolean self = k.child == k.parent; // e.g. EMPLOYEE ||--o{ EMPLOYEE : manages
      if (cc == null) {
        String pn = pc.name.toLowerCase(Locale.ROOT), parentName = squash(k.parent.name);
        // a lone primary key column cannot hold a one-to-many foreign key (it would make the line one-to-one)
        boolean pkOk = !Puml.isMany(k.toMult) || k.child.pks().size() > 1;
        for (Puml.Column c : k.child.cols) if (cc == null && c.fk && free(c, used, self) && c.name.equalsIgnoreCase(pn)) cc = c;
        for (Puml.Column c : k.child.cols) if (cc == null && c.fk && free(c, used, self) && c.name.toLowerCase(Locale.ROOT).contains(pn)) cc = c;
        for (Puml.Column c : k.child.cols) if (cc == null && c.fk && free(c, used, self) && squash(c.name).contains(parentName)) cc = c;
        for (Puml.Column c : k.child.cols) { // an FK column whose name does not point at any other table, e.g. manager_id
          if (cc == null && c.fk && free(c, used, self) && !namesOtherTable(d, c.name, k.parent) && claimedBy(d, k.child, c) == null) {
            cc = c;
            d.warnings.add(k.child.name + "." + c.name + " was taken as the foreign key to " + k.parent.name
                + " (add a ' vp-fk line under the relationship to choose another column)");
          }
        }
        // columns not tagged FK: same name as the parent key (customer_id), or parent name + id (customerId)
        for (Puml.Column c : k.child.cols) if (cc == null && free(c, used, self) && (pkOk || !c.pk) && c.name.equalsIgnoreCase(pn) && claimedBy(d, k.child, c) == null) cc = c;
        for (Puml.Column c : k.child.cols) {
          String sq = squash(c.name);
          if (cc == null && free(c, used, self) && !c.pk && sq.startsWith(parentName) && sq.endsWith("id") && claimedBy(d, k.child, c) == null) cc = c;
        }
      }
      if (cc == null) {
        String n = !self && k.child.col(pc.name) == null ? pc.name
            : (self ? "parent_" : k.parent.name.toLowerCase(Locale.ROOT) + "_") + pc.name;
        cc = pc.copyAs(n);
        cc.fk = true; cc.created = true; cc.mandatory = Puml.ONE.equals(k.fromMult);
        k.child.cols.add(cc);
      }
      cc.fk = true;
      used.add(cc.name);
      if (!cc.pk) identifying = false;
      k.pairs.add(new String[] {cc.name, pc.name});
    }
    k.identifying = identifying;
  }

  /** Lower case letters and digits only: "Client Type" and client_type both become clienttype. */
  static String squash(String s) { return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", ""); }

  private static boolean free(Puml.Column c, Set<String> used, boolean self) {
    return !used.contains(c.name) && !(self && c.pk);
  }

  /** True when the column name contains the name or primary key name of a table other than the given parent. */
  private static boolean namesOtherTable(Puml.Diagram d, String column, Puml.Entity parent) {
    String n = squash(column);
    for (Puml.Entity e : d.entities) {
      if (e == parent) continue;
      String en = squash(e.name);
      if (en.length() > 0 && n.contains(en)) return true;
      for (Puml.Column pk : e.pks()) if (squash(pk.name).length() > 2 && n.contains(squash(pk.name))) return true;
    }
    return false;
  }

  /** The relationship whose ' vp-fk hint names this column, if any. */
  private static Puml.Rel claimedBy(Puml.Diagram d, Puml.Entity child, Puml.Column c) {
    for (Puml.Rel r : d.rels) {
      Puml.Entity L = d.entity(r.left), Rt = d.entity(r.right);
      if (L != child && Rt != child) continue;
      for (String h : r.fkCols) if (h.equalsIgnoreCase(c.name)) return r;
    }
    return null;
  }

  private static void junction(Puml.Diagram d, Puml.Rel r, Puml.Entity a, Puml.Entity b, java.util.Map<Puml.Entity, Set<String>> used) {
    String name = a.name + "_" + b.name;
    Puml.Entity j = d.entity(name);
    if (j == null) {
      j = d.addEntity(name, null);
      for (Puml.Entity side : new Puml.Entity[] {a, b}) {
        for (Puml.Column pk : side.pks()) {
          String n = j.col(pk.name) == null ? pk.name : side.name.toLowerCase(Locale.ROOT) + "_" + pk.name;
          Puml.Column c = pk.copyAs(n);
          c.pk = true; c.fk = true; c.mandatory = true; c.created = true;
          j.cols.add(c);
        }
      }
    }
    d.warnings.add(a.name + " to " + b.name + " is many-to-many, so it was split with a new link table " + name);
    for (Puml.Entity side : new Puml.Entity[] {a, b}) {
      Puml.Link k = new Puml.Link();
      k.parent = side; k.child = j; k.fromMult = Puml.ONE; k.toMult = Puml.ZERO_TO_MANY; k.label = r.label;
      pairColumns(d, k, new ArrayList<String>(), used);
      d.links.add(k);
    }
  }
}
