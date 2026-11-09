package erdimport;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits PlantUML text into @startuml blocks, works out what kind of diagram each block is,
 * and reads class and use case diagrams into {@link Uml.Diagram}s.
 */
final class UmlParser {
  private UmlParser() {}

  /** One @startuml ... @enduml block. */
  static final class Block {
    final String text;
    final Uml.Kind kind;
    Block(String text, Uml.Kind kind) { this.text = text; this.kind = kind; }
  }

  // ------------------------------------------------------------------ blocks and detection

  static List<Block> split(String text) {
    text = text.replace("﻿", "").replace("\r\n", "\n").replace('\r', '\n');
    List<Block> out = new ArrayList<Block>();
    if (!text.contains("@startuml")) {
      if (text.trim().length() > 0) out.add(new Block(text, detect(text)));
      return out;
    }
    StringBuilder cur = null;
    for (String line : text.split("\n", -1)) {
      String t = line.trim().toLowerCase(Locale.ROOT);
      if (t.startsWith("@startuml")) cur = new StringBuilder();
      if (cur != null) cur.append(line).append('\n');
      if (t.startsWith("@enduml") && cur != null) {
        out.add(new Block(cur.toString(), detect(cur.toString())));
        cur = null;
      }
    }
    if (cur != null) out.add(new Block(cur.toString(), detect(cur.toString())));
    return out;
  }

  private static final Pattern CROWS_FOOT = Pattern.compile("(\\|o|\\|\\||\\}o|\\}\\|)[-.]+|[-.]+(o\\||\\|\\||o\\{|\\|\\{)");
  /** (Use case) or :Actor: at either end of an arrow, or starting a declaration line. */
  private static final Pattern UC_PAREN = Pattern.compile("^\\([^)]+\\)|[-.>]\\s*\\([^)]+\\)\\s*(:.*)?$");
  private static final Pattern ACTOR_COLON = Pattern.compile("^:[^:\\s][^:]*:|[-.>]\\s*:[^:\\s][^:]*:\\s*(:.*)?$");
  private static final Pattern SEQ_ARROW = Pattern.compile("^(\"[^\"]+\"|[\\w.$]+)\\s*(<?-{1,2}>{1,2}|<<?-{1,2}|-{1,2}\\\\\\\\|-{1,2}//|->x|-\\[[^\\]]*\\]->)\\s*(\"[^\"]+\"|[\\w.$]+)\\s*:.*$");

  /** Decides the diagram type from the keywords and arrows the block uses. */
  static Uml.Kind detect(String block) {
    boolean classKw = false, classOnly = false, ucKw = false, seqKw = false, actKw = false, stateKw = false, compKw = false,
        deployKw = false, infraKw = false, objectKw = false, erd = false, seqArrow = false, ucSyntax = false, actorKw = false,
        classArrow = false, actStrong = false;
    boolean inNote = false, legacy = false;
    int ucCount = 0, compCount = 0, deployCount = 0;
    for (String raw : block.split("\n")) {
      String line = raw.trim();
      String l = line.toLowerCase(Locale.ROOT);
      if (l.length() == 0 || l.startsWith("'") || l.startsWith("@")) continue;
      if (inNote) { if (l.startsWith("end note") || l.startsWith("endnote")) inNote = false; continue; }
      if (l.startsWith("note ") && !l.contains(":") && !l.contains("\"")) { inNote = true; continue; }
      if (l.startsWith("note ") || l.startsWith("skinparam") || l.startsWith("title")) continue;
      if (l.matches("^(entity)\\s.*\\{.*") || CROWS_FOOT.matcher(line).find()) erd = true;
      if (l.matches("^(abstract\\s+class|abstract|class|interface|enum|annotation|struct|exception|protocol|dataclass|record)\\s.*")) classKw = true;
      if (l.matches("^(abstract\\s+class|abstract|class|enum|annotation|struct|exception|protocol|dataclass|record)\\s.*")) classOnly = true;
      if (line.contains("(*)") || line.contains("(*top)")) legacy = true;
      if (l.matches("^usecase/?\\s.*")) { ucKw = true; ucCount++; }
      if (UC_PAREN.matcher(line).find() || ACTOR_COLON.matcher(line).find()) ucCount++;
      if (l.matches("^(component|port|portin|portout)\\s.*") || l.matches("^\\[[^\\]]+\\].*") || line.matches(".*[-.>]\\s*\\[[^\\]]+\\].*")) compCount++;
      if (l.matches("^(node|artifact|device|executionenvironment|execution_environment)\\s.*")) deployCount++;
      if (l.matches("^actor/?\\s.*")) actorKw = true;
      if (l.matches("^(participant|activate|deactivate|destroy|autonumber|alt|opt|loop|par|break|critical|ref\\s+over|return|create)(\\s.*)?$")
          || l.matches("^==.*==$") || l.matches("^\\.\\.\\..*") || l.equals("|||")) seqKw = true;
      if (l.matches("^(start|stop|end|kill|detach|fork|fork again|end fork|split|end split|endif|end if|endwhile|repeat|partition\\s.*|if\\s*\\(.*|while\\s*\\(.*|elseif\\s*\\(.*|else.*)$")
          || l.matches("^:.*;?$") && !ACTOR_COLON.matcher(line).find() || l.matches("^\\|[^|]+\\|.*")) actKw = true;
      if (l.matches("^(start|stop|endif|end if|endwhile|fork|end fork)$") || l.matches("^(if|while)\\s*\\(.*") || l.matches("^:.*;$")) actStrong = true;
      if (line.contains("[*]") || l.matches("^state\\s.*")) stateKw = true;
      if (l.matches("^(component|port|portin|portout)\\s.*") || l.matches("^\\[[^\\]]+\\].*") || line.matches(".*[-.>]\\s*\\[[^\\]]+\\].*")
          || l.matches("^\\(\\)\\s.*")) compKw = true;
      if (l.matches("^(node|artifact|device|executionenvironment|execution_environment)\\s.*")) deployKw = true;
      if (l.matches("^(cloud|frame|folder|agent|card|stack|storage|queue)\\s.*")) infraKw = true;
      if (l.matches("^(database|queue|stack|storage|collections|file)\\s.*")) infraKw = true;
      if (l.matches("^(object|map|json)\\s.*")) objectKw = true;
      if (UC_PAREN.matcher(line).find() || ACTOR_COLON.matcher(line).find()) ucSyntax = true;
      if (line.contains("<|") || line.contains("|>") || line.matches(".*\\s(\\*|o)[-.]+.*") || line.matches(".*[-.]+(\\*|o)\\s.*")) classArrow = true;
      if (SEQ_ARROW.matcher(line).matches()) seqArrow = true;
    }
    if (legacy && !classKw) return Uml.Kind.ACTIVITY;
    if (erd && !classKw) return Uml.Kind.ERD;
    if (stateKw && !classOnly) return Uml.Kind.STATE;
    if (actKw && !classKw && !ucKw && (!seqKw || actStrong)) return Uml.Kind.ACTIVITY;
    if (seqKw && !classOnly) return Uml.Kind.SEQUENCE;
    if ((ucKw || ucSyntax) && ucCount >= compCount + deployCount) return Uml.Kind.USECASE;
    if ((compKw || deployKw) && !classOnly) return deployCount > compCount ? Uml.Kind.DEPLOYMENT : Uml.Kind.COMPONENT;
    if (ucKw || ucSyntax) return Uml.Kind.USECASE;
    if (objectKw && !classOnly) return Uml.Kind.OBJECT;
    if (seqArrow && !classKw && !classArrow) return Uml.Kind.SEQUENCE;
    if (classKw || classArrow) return Uml.Kind.CLASS;
    if (infraKw) return Uml.Kind.DEPLOYMENT;
    if (actorKw) return Uml.Kind.USECASE;
    if (objectKw) return Uml.Kind.OBJECT;
    if (erd) return Uml.Kind.ERD;
    return Uml.Kind.UNKNOWN;
  }

  // ------------------------------------------------------------------ class and use case diagrams

  private static final String NAME = "(\"[^\"]+\"|\\([^)]+\\)|:[^:]+:|\\[[^\\]]+\\]|[\\w.$]++(?:<[^>]*>)?(?:::[\\w$]+)?)";
  private static final String MULT = "(?:\"([^\"]*)\")?";
  private static final Pattern REL = Pattern.compile(
      "^" + NAME + "\\s*" + MULT + "\\s*(<\\|-?|<\\||\\^|\\*|o|<|\\+|#|x|\\}|\\)|0\\))?"
      + "([-.]+)(?:\\[[^\\]]*\\])?(?:up|down|left|right|u|d|l|r)?(?:\\[[^\\]]*\\])?([-.]*)"
      + "(\\|>|\\*|o|>|\\+|#|x|\\{|\\^|\\(|\\(0)?\\s*" + MULT + "\\s*" + NAME + "\\s*(?::\\s*(.*))?$");
  private static final Pattern DECL = Pattern.compile(
      "^(abstract\\s+class|abstract|class|interface|enum|annotation|struct|exception|protocol|dataclass|record|metaclass|object)\\s+(.+?)\\s*$",
      Pattern.CASE_INSENSITIVE);
  private static final Pattern ACTOR_DECL = Pattern.compile("^actor/?\\s+(.+?)\\s*$", Pattern.CASE_INSENSITIVE);
  private static final Pattern UC_DECL = Pattern.compile("^usecase/?\\s+(.+?)\\s*$", Pattern.CASE_INSENSITIVE);
  private static final Pattern CONTAINER = Pattern.compile(
      "^(package|namespace|rectangle|frame|folder|cloud|node|database|card|together|component|artifact|device|storage|queue|stack|"
      + "executionenvironment|execution_environment|file|hexagon|agent)\\b\\s*(.*?)\\s*\\{\\s*$", Pattern.CASE_INSENSITIVE);
  /** Element keywords of component, deployment and object diagrams. */
  private static final Pattern ELEM = Pattern.compile(
      "^(component|interface|node|artifact|database|cloud|folder|frame|storage|file|queue|stack|card|rectangle|agent|person|boundary|"
      + "control|entity|collections|hexagon|label|device|executionenvironment|execution_environment|package|actor|usecase|port|portin|portout)"
      + "\\s+(.+?)\\s*$", Pattern.CASE_INSENSITIVE);
  private static final Pattern BRACKET = Pattern.compile("^(\\[[^\\]]+\\])\\s*(.*)$");
  private static final Pattern LOLLIPOP = Pattern.compile("^\\(\\)\\s+(.+)$");
  private static final Pattern STEREO = Pattern.compile("<<\\s*([^>]*?)\\s*>>");
  private static final Pattern MEMBER_OF = Pattern.compile("^(\"[^\"]+\"|[\\w.$]+)\\s*:\\s*(.+)$");
  private static final Pattern METHOD = Pattern.compile("^(?:(.+?)\\s+)?([\\w$~]+)\\s*\\((.*)\\)\\s*(?::\\s*(.+))?$");
  private static final Pattern NOTE_ON = Pattern.compile("^note\\s+(?:left|right|top|bottom)(?:\\s+of\\s+(.+?))?\\s*(?::\\s*(.*))?$", Pattern.CASE_INSENSITIVE);
  private static final Pattern NOTE_AS = Pattern.compile("^note\\s+\"(.*)\"\\s+as\\s+(\\S+)\\s*$", Pattern.CASE_INSENSITIVE);
  private static final Pattern NOTE_AS_BLOCK = Pattern.compile("^note\\s+as\\s+(\\S+)\\s*$", Pattern.CASE_INSENSITIVE);

  /** Parses any graph-style diagram (everything except ER and sequence diagrams). */
  static Uml.Diagram parseAny(Block block, String fallbackName) {
    if (block.kind == Uml.Kind.ACTIVITY) return FlowParser.activity(block, fallbackName);
    if (block.kind == Uml.Kind.STATE) return FlowParser.state(block, fallbackName);
    return parse(block, fallbackName);
  }

  private static Uml.Diagram currentDiagram; // for map entries that link to other objects

  static Uml.Diagram parse(Block block, String fallbackName) {
    Uml.Diagram d = new Uml.Diagram(block.kind);
    currentDiagram = d;
    boolean uc = block.kind == Uml.Kind.USECASE;
    boolean infra = block.kind == Uml.Kind.COMPONENT || block.kind == Uml.Kind.DEPLOYMENT;
    boolean obj = block.kind == Uml.Kind.OBJECT;
    String[] lines = block.text.split("\n", -1);
    List<Uml.Node> stack = new ArrayList<Uml.Node>(); // open containers (null for "together")
    Uml.Node open = null; // class whose { body } is being read
    Uml.Node note = null; // note whose text is being read
    StringBuilder noteText = null;
    String skipUntil = null;
    boolean inBlockComment = false;
    int braceSkip = 0;

    for (int i = 0; i < lines.length; i++) {
      String line = lines[i].trim();
      if (inBlockComment) { if (line.contains("'/")) inBlockComment = false; continue; }
      if (line.startsWith("/'")) { inBlockComment = !line.substring(2).contains("'/"); continue; }
      String lower = line.toLowerCase(Locale.ROOT);
      if (lower.startsWith("@startuml")) {
        String n = line.substring(9).trim();
        if (n.length() > 0 && !n.startsWith("(")) d.title = n;
        continue;
      }
      if (lower.startsWith("@enduml")) break;
      if (note != null) {
        if (lower.startsWith("end note") || lower.startsWith("endnote")) { note.text = noteText.toString().trim(); note = null; }
        else noteText.append(line).append('\n');
        continue;
      }
      if (skipUntil != null) { if (lower.matches(skipUntil)) skipUntil = null; continue; }
      if (braceSkip > 0) {
        if (line.contains("{")) braceSkip++;
        if (line.contains("}")) braceSkip--;
        continue;
      }
      if (line.length() == 0 || line.startsWith("'")) continue;

      if (open != null) {
        if (line.startsWith("}")) { open = null; continue; }
        member(open, line);
        continue;
      }
      if (line.equals("}")) { if (!stack.isEmpty()) stack.remove(stack.size() - 1); continue; }

      // notes
      Matcher m;
      if ((m = NOTE_AS.matcher(line)).matches()) {
        Uml.Node n = d.add(Uml.NOTE, m.group(2), m.group(2));
        n.text = m.group(1).replace("\\n", "\n");
        n.parent = top(stack);
        continue;
      }
      if ((m = NOTE_AS_BLOCK.matcher(line)).matches()) {
        note = d.add(Uml.NOTE, m.group(1), m.group(1));
        note.parent = top(stack);
        noteText = new StringBuilder();
        continue;
      }
      if ((m = NOTE_ON.matcher(line)).matches()) {
        Uml.Node n = d.add(Uml.NOTE, "note" + (d.count(Uml.NOTE) + 1), null);
        n.parent = top(stack);
        Uml.Node target = m.group(1) == null ? null : endpoint(d, m.group(1), uc, stack);
        if (target != null) { Uml.Link k = new Uml.Link(); k.kind = Uml.ANCHOR; k.from = n; k.to = target; k.line = i + 1; d.links.add(k); }
        if (m.group(2) != null) n.text = m.group(2).replace("\\n", "\n");
        else { note = n; noteText = new StringBuilder(); }
        continue;
      }
      if (lower.startsWith("note ")) { if (!line.contains(":")) skipUntil = "^end\\s*note.*"; continue; }

      if (lower.equals("left to right direction")) { d.leftToRight = true; continue; }
      if (lower.startsWith("title ")) { d.title = line.substring(6).trim(); continue; }
      if (lower.equals("title")) { skipUntil = "^end\\s*title.*"; continue; }
      if (lower.startsWith("legend") || lower.equals("header") || lower.equals("footer") || lower.equals("caption")) {
        skipUntil = "^end\\s*(legend|header|footer|caption).*"; continue;
      }
      if (lower.startsWith("skinparam") || lower.startsWith("!") || lower.startsWith("hide") || lower.startsWith("show")
          || lower.startsWith("top to bottom") || lower.startsWith("scale") || lower.startsWith("header ")
          || lower.startsWith("footer ") || lower.startsWith("caption ") || lower.startsWith("set ") || lower.startsWith("allowmixing")
          || lower.startsWith("remove ") || lower.startsWith("restore ") || lower.startsWith("newpage")) {
        if (line.endsWith("{")) braceSkip = 1;
        continue;
      }
      if (lower.contains("[hidden]") || lower.contains("[hidden,") || lower.contains(",hidden]")) continue;

      // containers: package / rectangle (system boundary) / together
      if ((m = CONTAINER.matcher(line)).matches()) {
        String kw = m.group(1).toLowerCase(Locale.ROOT);
        if (kw.equals("together") || m.group(2).length() == 0) { stack.add(null); continue; }
        String[] na = nameAlias(clean(m.group(2)));
        List<String> stereos = new ArrayList<String>();
        String[] na2 = nameAlias(clean(m.group(2), stereos));
        na = new String[] {strip(na2[0]), na2[1]};
        String kind = infra ? elemKind(kw, d.kind) : uc && !kw.equals("package") && !kw.equals("namespace") && !kw.equals("folder") ? Uml.SYSTEM : Uml.PACKAGE;
        Uml.Node c = d.add(kind, na[0], na[1]);
        if (c.parent == null) c.parent = top(stack);
        c.container = true;
        if (infra) stereotypeFor(c, kw);
        for (String st : stereos) if (!c.stereotypes.contains(st)) c.stereotypes.add(st);
        stack.add(c);
        continue;
      }

      // relationships
      Matcher r = REL.matcher(line);
      if (r.matches() && relation(d, r, uc, stack, i + 1)) continue;

      // component / deployment / object diagram elements
      if (infra || obj) {
        Matcher e;
        if ((e = BRACKET.matcher(line)).matches()) { element(d, Uml.COMPONENT, "component", e.group(1) + " " + e.group(2), stack); continue; }
        if ((e = LOLLIPOP.matcher(line)).matches()) { element(d, Uml.PORT_INTERFACE, "interface", e.group(1), stack); continue; }
        if ((e = ELEM.matcher(line)).matches() && !(obj && e.group(1).equalsIgnoreCase("object"))) {
          String kw = e.group(1).toLowerCase(Locale.ROOT);
          element(d, elemKind(kw, d.kind), kw, e.group(2), stack);
          continue;
        }
        if (obj && (e = Pattern.compile("^(object|map|json)\\s+(.+?)\\s*$", Pattern.CASE_INSENSITIVE).matcher(line)).matches()) {
          String rest = e.group(2);
          boolean body = rest.endsWith("{");
          if (body) rest = rest.substring(0, rest.length() - 1).trim();
          Uml.Node n = element(d, Uml.OBJECT, "object", rest, stack);
          if (body) { open = n; if (e.group(1).equalsIgnoreCase("map")) n.stereotypes.add("map"); }
          continue;
        }
      }

      // declarations
      if ((m = DECL.matcher(line)).matches()) { open = declare(d, m.group(1), m.group(2), stack); continue; }
      if ((m = ACTOR_DECL.matcher(line)).matches()) { declareSimple(d, Uml.ACTOR, m.group(1), stack); continue; }
      if ((m = UC_DECL.matcher(line)).matches()) { declareSimple(d, Uml.USECASE, m.group(1), stack); continue; }
      if (uc && (line.startsWith("(") || line.startsWith(":"))) { // (Use case) as UC1   :Actor: as A
        boolean actor = line.startsWith(":");
        declareSimple(d, actor ? Uml.ACTOR : Uml.USECASE, line, stack);
        continue;
      }

      // ClassName : member
      if ((m = MEMBER_OF.matcher(line)).matches() && !uc) {
        Uml.Node n = d.node(PumlParser.unquote(m.group(1)));
        if (n == null) { n = d.add(obj ? Uml.OBJECT : Uml.CLASS, PumlParser.unquote(m.group(1)), null); n.parent = top(stack); }
        n.implicit = false;
        member(n, m.group(2));
        continue;
      }
      d.warnings.add("line " + (i + 1) + " was not understood and was skipped: " + line);
    }
    if (d.title == null || d.title.length() == 0) d.title = fallbackName;
    d.title = d.title.replaceAll("<[^>]+>", "").replace("\\n", " ").trim();
    return d;
  }

  private static Uml.Node top(List<Uml.Node> stack) {
    for (int i = stack.size() - 1; i >= 0; i--) if (stack.get(i) != null) return stack.get(i);
    return null;
  }

  /** Removes colours, stereotypes and links from a declaration, returning stereotypes through the list. */
  private static String clean(String s, List<String> stereos) {
    Matcher m = STEREO.matcher(s);
    while (m.find()) if (stereos != null) for (String st : m.group(1).split(",")) if (st.trim().length() > 0) stereos.add(st.trim());
    s = m.replaceAll(" ");
    // colours (#red, ##[dashed]blue) and links ([[url]]) go, but not inside quoted names such as "Order #1"
    StringBuilder out = new StringBuilder();
    Matcher q = Pattern.compile("\"[^\"]*\"").matcher(s);
    int last = 0;
    while (q.find()) { out.append(uncolour(s.substring(last, q.start()))).append(q.group()); last = q.end(); }
    out.append(uncolour(s.substring(last)));
    return out.toString().trim();
  }

  private static String uncolour(String s) {
    return s.replaceAll("\\[\\[[^\\]]*\\]\\]", " ").replaceAll("(^|\\s)##?\\[[^\\]]*\\]\\S*", " ").replaceAll("(^|\\s)#[\\w;:.#/-]+", " ");
  }

  private static String clean(String s) { return clean(s, null); }

  /** "Long name" as A,  A as "Long name",  Name as A,  Name  ->  {name, alias} */
  private static String[] nameAlias(String rest) {
    Matcher m;
    if ((m = Pattern.compile("^\"([^\"]+)\"\\s+as\\s+(\\S+)$").matcher(rest)).matches()) return new String[] {m.group(1), m.group(2)};
    if ((m = Pattern.compile("^(\\S+)\\s+as\\s+\"([^\"]+)\"$").matcher(rest)).matches()) return new String[] {m.group(2), m.group(1)};
    if ((m = Pattern.compile("^(\\S+)\\s+as\\s+(\\S+)$").matcher(rest)).matches()) return new String[] {m.group(1), m.group(2)};
    return new String[] {PumlParser.unquote(rest), null};
  }

  private static Uml.Node declare(Uml.Diagram d, String keyword, String rest, List<Uml.Node> stack) {
    String kw = keyword.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    boolean body = false;
    String inline = null;
    Matcher one = Pattern.compile("^([^{]*)\\{(.*)\\}\\s*$").matcher(rest);
    if (rest.endsWith("{")) { body = true; rest = rest.substring(0, rest.length() - 1).trim(); }
    else if (one.matches()) { inline = one.group(2).trim(); rest = one.group(1).trim(); }
    List<String> stereos = new ArrayList<String>();
    rest = clean(rest, stereos);
    rest = dropGenerics(rest); // Foo<? extends Element> must not be read as "Foo<?" extends "Element>"
    List<String> parents = new ArrayList<String>(), ifaces = new ArrayList<String>();
    Matcher m = Pattern.compile("\\s+implements\\s+(.+)$", Pattern.CASE_INSENSITIVE).matcher(rest);
    if (m.find()) { for (String s : m.group(1).split(",")) ifaces.add(PumlParser.unquote(s)); rest = rest.substring(0, m.start()).trim(); }
    m = Pattern.compile("\\s+extends\\s+(.+)$", Pattern.CASE_INSENSITIVE).matcher(rest);
    if (m.find()) { for (String s : m.group(1).split(",")) parents.add(PumlParser.unquote(s)); rest = rest.substring(0, m.start()).trim(); }
    String[] na = nameAlias(rest);
    String name = na[0].replaceAll("<[^>]*>$", "").trim(); // generic parameters are not part of the name
    String kind = kw.equals("interface") || kw.equals("protocol") ? Uml.INTERFACE : kw.equals("enum") ? Uml.ENUM
        : kw.equals("annotation") ? Uml.ANNOTATION : Uml.CLASS;
    Uml.Node n = d.add(kind, name, na[1]);
    if (n.parent == null) n.parent = top(stack);
    if (kw.startsWith("abstract")) n.isAbstract = true;
    if (!kind.equals(Uml.CLASS) || kw.equals("class") || kw.startsWith("abstract")) { /* plain */ }
    else if (!kw.equals("object")) n.stereotypes.add(kw); // struct, exception, record ...
    for (String s : stereos) if (!n.stereotypes.contains(s)) n.stereotypes.add(s);
    for (String p : parents) {
      Uml.Link k = new Uml.Link();
      Uml.Node pn = endpoint(d, p, false, stack);
      k.kind = Uml.INTERFACE.equals(kind) || !Uml.INTERFACE.equals(pn.kind) ? Uml.GENERALIZATION : Uml.REALIZATION;
      k.from = pn; k.to = n;
      d.links.add(k);
    }
    for (String p : ifaces) {
      Uml.Link k = new Uml.Link();
      Uml.Node pn = endpoint(d, p, false, stack);
      if (pn.implicit) pn.kind = Uml.INTERFACE;
      k.kind = Uml.REALIZATION; k.from = pn; k.to = n;
      d.links.add(k);
    }
    if (inline != null && inline.length() > 0) {
      if (Uml.ENUM.equals(kind)) member(n, inline);
      else for (String part : inline.split(";")) member(n, part);
    }
    return body ? n : null;
  }

  private static void declareSimple(Uml.Diagram d, String kind, String rest, List<Uml.Node> stack) {
    if (rest.endsWith("{")) rest = rest.substring(0, rest.length() - 1).trim();
    List<String> stereos = new ArrayList<String>();
    rest = clean(rest, stereos);
    String name, alias = null;
    Matcher m = Pattern.compile("^(\\([^)]+\\)|:[^:]+:|\"[^\"]+\"|\\S+)\\s+as\\s+(\\([^)]+\\)|:[^:]+:|\"[^\"]+\"|\\S+)$").matcher(rest);
    if (m.matches()) {
      String a = m.group(1), b = m.group(2);
      boolean aIsLabel = a.startsWith("\"") || a.startsWith("(") || a.startsWith(":");
      boolean bIsLabel = b.startsWith("\"") || b.startsWith("(") || b.startsWith(":");
      if (bIsLabel && !aIsLabel) { name = strip(b); alias = a; } else { name = strip(a); alias = strip(b); }
    } else name = strip(rest);
    Uml.Node n = d.add(kind, name.replace("\\n", " "), alias);
    if (n.parent == null) n.parent = top(stack);
    for (String s : stereos) if (!n.stereotypes.contains(s)) n.stereotypes.add(s);
  }

  /** Removes a generic parameter list such as <T> or <K, V extends X> that follows the first name. */
  static String dropGenerics(String s) {
    int lt = -1;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '"') { int q = s.indexOf('"', i + 1); if (q < 0) break; i = q; continue; }
      if (c == '<' && (i + 1 >= s.length() || s.charAt(i + 1) != '<') && (i == 0 || s.charAt(i - 1) != '<')) { lt = i; break; }
      if (Character.isWhitespace(c)) break; // only directly after the name
    }
    if (lt < 0) return s;
    int depth = 0;
    for (int i = lt; i < s.length(); i++) {
      if (s.charAt(i) == '<') depth++;
      else if (s.charAt(i) == '>' && --depth == 0) return (s.substring(0, lt) + s.substring(i + 1)).trim();
    }
    return s;
  }

  /** "x", (x), :x: -> x */
  private static String strip(String s) {
    s = s.trim();
    if (s.length() >= 2 && (s.startsWith("(") && s.endsWith(")") || s.startsWith(":") && s.endsWith(":") || s.startsWith("\"") && s.endsWith("\""))) {
      s = s.substring(1, s.length() - 1);
    }
    return s.trim();
  }

  /** The node a relationship end names, adding it when it has not been declared. */
  private static Uml.Node endpoint(Uml.Diagram d, String raw, boolean uc, List<Uml.Node> stack) {
    raw = raw.trim();
    String kind = raw.startsWith("(") ? Uml.USECASE : raw.startsWith(":") ? Uml.ACTOR : raw.startsWith("[") ? Uml.COMPONENT : null;
    String name = strip(raw).replace("\\n", " ");
    if (raw.startsWith("[") && raw.endsWith("]")) name = raw.substring(1, raw.length() - 1).replace("\\n", " ").trim();
    if (kind == null) name = name.replaceAll("<[^>]*>$", "").replaceAll("::[\\w$]+$", ""); // Map::key -> Map
    Uml.Node n = d.node(name);
    if (n != null && (kind == null || kind.equals(n.kind) || n.implicit)) return n;
    if (n != null) { // (checkout) inside rectangle checkout: a use case of the same name as something else
      Uml.Node u = d.node(kind + "\u0000" + name);
      if (u == null) { u = d.keyed(kind, name, kind + "\u0000" + name); u.parent = top(stack); }
      return u;
    }
    if (kind == null) {
      Uml.Kind dk = d.kind;
      // in a component diagram a bare name is an interface, as in PlantUML
      kind = uc ? Uml.ACTOR : dk == Uml.Kind.COMPONENT ? Uml.PORT_INTERFACE : dk == Uml.Kind.DEPLOYMENT ? Uml.NODE
          : dk == Uml.Kind.OBJECT ? Uml.OBJECT : Uml.CLASS;
    }
    n = d.add(kind, name, null);
    n.implicit = !raw.startsWith("(") && !raw.startsWith(":") && !raw.startsWith("[");
    n.parent = top(stack);
    return n;
  }

  private static boolean relation(Uml.Diagram d, Matcher r, boolean uc, List<Uml.Node> stack, int lineNo) {
    String leftRaw = r.group(1), rightRaw = r.group(8);
    String lh = r.group(3), rh = r.group(6);
    String body = r.group(4) + r.group(5);
    if (lh != null && lh.endsWith("-")) { lh = lh.substring(0, lh.length() - 1); body = "-" + body; }
    boolean dashed = body.contains(".");
    String label = r.group(9) == null ? null : r.group(9).trim();
    Uml.Node L = endpoint(d, leftRaw, uc, stack), R = endpoint(d, rightRaw, uc, stack);
    Uml.Link k = new Uml.Link();
    k.line = lineNo;
    String lmult = r.group(2), rmult = r.group(7);
    boolean include = false, extend = false;
    if (label != null) {
      String ll = label.toLowerCase(Locale.ROOT);
      include = ll.matches(".*(<<\\s*include\\s*>>|^include$|«include»).*");
      extend = ll.matches(".*(<<\\s*extends?\\s*>>|^extends?$|«extend»).*");
      label = label.replaceAll("<<[^>]*>>", "").replaceAll("^[<>]\\s*|\\s*[<>]$", "").replace("\\n", " ").trim();
      if (label.startsWith("\"") && label.endsWith("\"") && label.length() > 1) label = label.substring(1, label.length() - 1);
      if (label.length() == 0 || label.matches("(?i)include|extends?")) label = null;
    }
    if (L.kind.equals(Uml.NOTE) || R.kind.equals(Uml.NOTE)) {
      k.kind = Uml.ANCHOR; k.from = L.kind.equals(Uml.NOTE) ? L : R; k.to = k.from == L ? R : L;
      d.links.add(k);
      return true;
    }
    boolean headLeft = lh != null && (lh.equals("<") || lh.equals("<|") || lh.equals("^"));
    boolean headRight = rh != null && (rh.equals(">") || rh.equals("|>") || rh.equals("^"));
    if (include || extend) {
      k.kind = include ? Uml.INCLUDE : Uml.EXTEND;
      // PlantUML: the arrow goes from the base use case to the included one, and from the extension to the base
      Uml.Node tail = headLeft && !headRight ? R : L, head = tail == L ? R : L;
      // Visual Paradigm: an include goes from the base to the included use case, but an extend goes from the
      // extended (base) use case to the extension (the arrow head and extension point are drawn at "from")
      k.from = include ? tail : head;
      k.to = include ? head : tail;
    } else if ("<|".equals(lh) || "^".equals(lh) || "|>".equals(rh) || "^".equals(rh)) {
      boolean parentLeft = "<|".equals(lh) || "^".equals(lh);
      Uml.Node parent = parentLeft ? L : R, child = parentLeft ? R : L;
      k.kind = dashed ? Uml.REALIZATION : Uml.GENERALIZATION;
      if (dashed && parent.implicit) parent.kind = Uml.INTERFACE;
      k.from = parent; k.to = child;
    } else if ("*".equals(lh) || "o".equals(lh) || "*".equals(rh) || "o".equals(rh)) {
      boolean wholeLeft = "*".equals(lh) || "o".equals(lh);
      String head = wholeLeft ? lh : rh;
      k.kind = Uml.ASSOCIATION;
      k.aggregation = head.equals("*") ? Uml.AGG_COMPOSITE : Uml.AGG_SHARED;
      k.from = wholeLeft ? L : R; k.to = wholeLeft ? R : L;
      k.fromMult = wholeLeft ? lmult : rmult; k.toMult = wholeLeft ? rmult : lmult;
      k.directed = wholeLeft ? ">".equals(rh) : "<".equals(lh);
    } else if (dashed) {
      k.kind = Uml.DEPENDENCY;
      k.from = headLeft && !headRight ? R : L;
      k.to = k.from == L ? R : L;
    } else {
      k.kind = Uml.ASSOCIATION;
      boolean flip = headLeft && !headRight;
      k.from = flip ? R : L; k.to = flip ? L : R;
      k.fromMult = flip ? rmult : lmult; k.toMult = flip ? lmult : rmult;
      k.directed = headLeft != headRight;
      if (uc && k.to.kind.equals(Uml.ACTOR) && !k.from.kind.equals(Uml.ACTOR) && !k.directed) { // keep actor -> use case order
        Uml.Node t = k.from; k.from = k.to; k.to = t;
        String tm = k.fromMult; k.fromMult = k.toMult; k.toMult = tm;
      }
    }
    if (k.from == k.to && !Uml.ASSOCIATION.equals(k.kind)) {
      d.warnings.add("line " + lineNo + ": " + k.kind + " from " + k.from.name + " to itself was skipped");
      return true;
    }
    if (d.kind == Uml.Kind.COMPONENT || d.kind == Uml.Kind.DEPLOYMENT) {
      boolean socket = "(".equals(rh) || "(0".equals(rh) || ")".equals(lh) || "0)".equals(lh);
      Uml.Node iface = Uml.PORT_INTERFACE.equals(k.to.kind) ? k.to : Uml.PORT_INTERFACE.equals(k.from.kind) ? k.from : null;
      if (socket) { // [A] -( I   requires I
        Uml.Node user = ")".equals(lh) || "0)".equals(lh) ? R : L, used = user == L ? R : L;
        k.kind = Uml.USAGE; k.from = user; k.to = used;
      } else if (Uml.ASSOCIATION.equals(k.kind) && !k.directed && iface != null && Uml.AGG_NONE.equals(k.aggregation)) {
        Uml.Node owner = iface == k.to ? k.from : k.to; // [A] - I   provides I
        k.kind = Uml.REALIZATION; k.from = iface; k.to = owner;
      } else if ((Uml.DEPENDENCY.equals(k.kind) || Uml.ASSOCIATION.equals(k.kind) && k.directed) && iface != null && iface == k.to) {
        k.kind = Uml.USAGE; // [A] --> I   requires I
      } else if (Uml.ASSOCIATION.equals(k.kind) && k.directed && d.kind == Uml.Kind.COMPONENT) {
        k.kind = Uml.DEPENDENCY;
      }
    } else if (d.kind == Uml.Kind.OBJECT && Uml.ASSOCIATION.equals(k.kind)) {
      k.kind = Uml.LINK;
    }
    k.label = label;
    d.links.add(k);
    return true;
  }

  /** The node kind a component/deployment keyword stands for. */
  private static String elemKind(String kw, Uml.Kind dk) {
    if (kw.equals("component")) return Uml.COMPONENT;
    if (kw.equals("interface")) return Uml.PORT_INTERFACE;
    if (kw.equals("artifact") || kw.equals("file")) return Uml.ARTIFACT;
    if (kw.equals("actor")) return Uml.ACTOR;
    if (kw.equals("usecase")) return Uml.USECASE;
    if (kw.equals("object")) return Uml.OBJECT;
    if (kw.equals("package") || kw.equals("folder") || kw.equals("frame") || kw.equals("rectangle") || kw.equals("card")) return Uml.PACKAGE;
    if (kw.equals("node") || kw.equals("device") || kw.startsWith("execution")) return Uml.NODE;
    if (kw.equals("database") || kw.equals("cloud") || kw.equals("storage") || kw.equals("queue") || kw.equals("stack"))
      return dk == Uml.Kind.DEPLOYMENT ? Uml.NODE : Uml.COMPONENT;
    return Uml.COMPONENT; // agent, person, boundary, control, entity, collections, hexagon, label, port
  }

  /** Keeps the PlantUML shape keyword as a stereotype where VP has no shape of its own (database, cloud, device ...). */
  private static void stereotypeFor(Uml.Node n, String kw) {
    String st = kw.equals("executionenvironment") || kw.equals("execution_environment") ? "execution environment"
        : kw.matches("database|cloud|storage|queue|stack|device|agent|person|boundary|control|entity|collections|hexagon|label|file|frame|folder|card|port|portin|portout") ? kw : null;
    if (st != null && !n.stereotypes.contains(st)) n.stereotypes.add(st);
  }

  /** Declares a component, interface, node, artifact or object: "Name" as A, [Name] as A, Name <<stereo>> ... */
  private static Uml.Node element(Uml.Diagram d, String kind, String kw, String rest, List<Uml.Node> stack) {
    if (rest.endsWith("{")) rest = rest.substring(0, rest.length() - 1).trim();
    List<String> stereos = new ArrayList<String>();
    rest = clean(rest, stereos);
    Matcher m = Pattern.compile("^(\\[[^\\]]+\\]|\"[^\"]+\"|\\S+)\\s+as\\s+(\\[[^\\]]+\\]|\"[^\"]+\"|\\S+)$").matcher(rest);
    String name, alias = null;
    if (m.matches()) {
      String a = m.group(1), b = m.group(2);
      boolean bLabel = b.startsWith("\"") || b.startsWith("[");
      boolean aLabel = a.startsWith("\"") || a.startsWith("[");
      if (bLabel && !aLabel) { name = unwrap(b); alias = a; } else { name = unwrap(a); alias = unwrap(b); }
    } else {
      Matcher desc = Pattern.compile("^(\\S+)\\s+\\[(.*)\\]$", Pattern.DOTALL).matcher(rest);
      if (desc.matches() && !rest.startsWith("[")) { // component C1 [long description]
        name = unwrap(desc.group(1));
        alias = desc.group(1);
        Uml.Node n = d.add(kind, name, alias);
        if (n.parent == null) n.parent = top(stack);
        n.text = desc.group(2).replace("\\n", "\n").trim();
        stereotypeFor(n, kw);
        for (String s : stereos) if (!n.stereotypes.contains(s)) n.stereotypes.add(s);
        return n;
      }
      name = unwrap(rest);
    }
    if (Uml.OBJECT.equals(kind)) name = name.replaceAll("\\s*:\\s*", " : ");
    Uml.Node n = d.add(kind, name.replace("\\n", " "), alias);
    if (n.parent == null) n.parent = top(stack);
    stereotypeFor(n, kw);
    for (String s : stereos) if (!n.stereotypes.contains(s)) n.stereotypes.add(s);
    return n;
  }

  private static String unwrap(String s) {
    s = s.trim();
    if (s.length() >= 2 && s.startsWith("[") && s.endsWith("]")) return s.substring(1, s.length() - 1).trim();
    return strip(s);
  }

  // ------------------------------------------------------------------ members

  private static void member(Uml.Node n, String line) {
    String s = line.trim();
    if (s.length() == 0 || s.matches("^(--|==|\\.\\.|__).*") || s.startsWith("'")) return;
    Matcher ml = Pattern.compile("^(.+?)\\s*\\*-+>\\s*(\\S+)$").matcher(s);
    if (Uml.OBJECT.equals(n.kind) && ml.matches() && currentDiagram != null) { // map entry linked to an object
      String slot = ml.group(1).trim() + " = " + PumlParser.unquote(ml.group(2));
      if (!n.slots.contains(slot)) n.slots.add(slot);
      Uml.Node target = currentDiagram.node(PumlParser.unquote(ml.group(2)));
      if (target == null) target = currentDiagram.add(Uml.OBJECT, PumlParser.unquote(ml.group(2)), null);
      Uml.Link k = currentDiagram.link(Uml.LINK, n, target, ml.group(1).trim());
      k.directed = true;
      return;
    }
    if (Uml.OBJECT.equals(n.kind)) { // name = value   (map: key => value)
      String slot = s.replace("=>", "=").replaceAll("\\s*=\\s*", " = ");
      if (!n.slots.contains(slot)) n.slots.add(slot);
      return;
    }
    if (Uml.ENUM.equals(n.kind) && !s.contains(":") && !s.contains("(") || Uml.ENUM.equals(n.kind) && s.matches("^[\\w$]+\\s*\\(.*\\)\\s*[,;]?$") && !s.contains(":")) {
      for (String lit : s.replaceAll(";.*$", "").split(",")) {
        lit = lit.replaceAll("\\(.*\\)", "").replaceAll("<<[^>]*>>", "").trim();
        if (lit.matches("[\\w$]+") && !n.literals.contains(lit)) n.literals.add(lit);
      }
      return;
    }
    Uml.Member m = new Uml.Member();
    boolean forceField = false, forceMethod = false;
    for (boolean again = true; again;) {
      again = false;
      Matcher mod = Pattern.compile("^\\{(static|classifier|abstract|field|method)\\}\\s*", Pattern.CASE_INSENSITIVE).matcher(s);
      if (mod.find()) {
        String t = mod.group(1).toLowerCase(Locale.ROOT);
        if (t.equals("static") || t.equals("classifier")) m.isStatic = true;
        else if (t.equals("abstract")) m.isAbstract = true;
        else if (t.equals("field")) forceField = true;
        else forceMethod = true;
        s = s.substring(mod.end());
        again = true;
      }
      if (s.length() > 0 && m.visibility == null && "+-#~".indexOf(s.charAt(0)) >= 0) {
        char c = s.charAt(0);
        m.visibility = c == '+' ? "public" : c == '-' ? "private" : c == '#' ? "protected" : "package";
        s = s.substring(1).trim();
        again = true;
      }
      Matcher kw = Pattern.compile("^(static|abstract|final|public|private|protected)\\s+").matcher(s);
      if (kw.find() && !s.substring(kw.end()).startsWith(":") && !s.substring(kw.end()).startsWith("(")) {
        String t = kw.group(1);
        if (t.equals("static")) m.isStatic = true;
        else if (t.equals("abstract")) m.isAbstract = true;
        else if (!t.equals("final") && m.visibility == null) m.visibility = t;
        s = s.substring(kw.end());
        again = true;
      }
    }
    s = s.replaceAll("<<[^>]*>>", "").trim();
    if (s.length() == 0) return;
    Matcher mm = METHOD.matcher(s);
    if (!forceField && (forceMethod || s.contains("(")) && mm.matches()) {
      m.method = true;
      m.name = mm.group(2);
      String ret = mm.group(4) != null ? mm.group(4).trim() : mm.group(1) != null ? mm.group(1).trim() : null;
      if (ret != null && ret.length() > 0) m.type = ret;
      for (String p : splitTop(mm.group(3))) {
        p = p.trim();
        if (p.length() == 0) continue;
        Uml.Param pr = new Uml.Param();
        Matcher dir = Pattern.compile("^(in|out|inout)\\s+", Pattern.CASE_INSENSITIVE).matcher(p);
        if (dir.find()) { pr.direction = dir.group(1).toLowerCase(Locale.ROOT); p = p.substring(dir.end()); }
        int eq = topIndex(p, '=');
        if (eq >= 0) { pr.defaultValue = p.substring(eq + 1).trim(); p = p.substring(0, eq).trim(); }
        String[] nt = nameType(p);
        pr.name = nt[0]; pr.type = nt[1];
        m.params.add(pr);
      }
    } else if (forceMethod) { // {method} free text
      m.method = true;
      int colon = topIndex(s, ':');
      m.name = colon >= 0 ? s.substring(0, colon).trim() : s;
      m.type = colon >= 0 ? emptyToNull(s.substring(colon + 1).trim()) : null;
    } else if (forceField && topIndex(s, ':') < 0) { // {field} free text, kept as written
      m.name = s;
    } else {
      int eq = topIndex(s, '=');
      if (eq >= 0) { m.initial = s.substring(eq + 1).trim(); s = s.substring(0, eq).trim(); }
      String[] nt = nameType(s);
      m.name = nt[0]; m.type = nt[1];
    }
    if (m.name == null || m.name.length() == 0) return;
    if (n.member(m.name, m.method) == null || m.method) n.members.add(m);
  }

  /** "name : Type", "Type name", "name" -> {name, type} */
  private static String[] nameType(String s) {
    s = s.trim();
    int colon = topIndex(s, ':');
    if (colon >= 0) return new String[] {s.substring(0, colon).trim(), emptyToNull(s.substring(colon + 1).trim())};
    int sp = lastTopSpace(s);
    if (sp > 0) return new String[] {s.substring(sp + 1).trim(), emptyToNull(s.substring(0, sp).trim())};
    return new String[] {s, null};
  }

  private static String emptyToNull(String s) { return s == null || s.length() == 0 ? null : s; }

  /** Splits on commas that are not inside <>, (), [] or {}. */
  private static List<String> splitTop(String s) {
    List<String> out = new ArrayList<String>();
    int depth = 0, start = 0;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '<' || c == '(' || c == '[' || c == '{') depth++;
      else if ((c == '>' || c == ')' || c == ']' || c == '}') && depth > 0) depth--;
      else if (c == ',' && depth == 0) { out.add(s.substring(start, i)); start = i + 1; }
    }
    out.add(s.substring(start));
    return out;
  }

  private static int topIndex(String s, char want) {
    int depth = 0;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '<' || c == '(' || c == '[' || c == '{') depth++;
      else if ((c == '>' || c == ')' || c == ']' || c == '}') && depth > 0) depth--;
      else if (c == want && depth == 0) return i;
    }
    return -1;
  }

  private static int lastTopSpace(String s) {
    int depth = 0, last = -1;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '<' || c == '(' || c == '[' || c == '{') depth++;
      else if ((c == '>' || c == ')' || c == ']' || c == '}') && depth > 0) depth--;
      else if (c == ' ' && depth == 0) last = i;
    }
    return last;
  }
}
