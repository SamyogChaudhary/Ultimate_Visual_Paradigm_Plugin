package erdimport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The parsed content of one PlantUML UML diagram (class or use case). Plain Java: no Visual Paradigm types. */
final class Uml {
  private Uml() {}

  /** What kind of diagram a @startuml block holds. */
  enum Kind {
    ERD("ER diagram"), CLASS("class diagram"), USECASE("use case diagram"), SEQUENCE("sequence diagram"),
    ACTIVITY("activity diagram"), STATE("state machine diagram"), COMPONENT("component diagram"),
    DEPLOYMENT("deployment diagram"), OBJECT("object diagram"), UNKNOWN("diagram");
    final String label;
    Kind(String label) { this.label = label; }
  }

  // node kinds
  static final String CLASS = "class", INTERFACE = "interface", ENUM = "enum", ANNOTATION = "annotation",
      ACTOR = "actor", USECASE = "usecase", PACKAGE = "package", SYSTEM = "system", NOTE = "note";

  // activity and state machine node kinds
  static final String ACTION = "action", INITIAL = "initial", FINAL = "final", FLOW_FINAL = "flow final",
      DECISION = "decision", MERGE = "merge", FORK = "fork", JOIN = "join", LANE = "swimlane",
      STATE = "state", CHOICE = "choice", HISTORY = "history", DEEP_HISTORY = "deep history";

  // component, deployment and object node kinds
  static final String COMPONENT = "component", PORT_INTERFACE = "provided interface", NODE = "node", ARTIFACT = "artifact",
      OBJECT = "object";

  // link kinds
  static final String ASSOCIATION = "association", GENERALIZATION = "generalization", REALIZATION = "realization",
      DEPENDENCY = "dependency", INCLUDE = "include", EXTEND = "extend", ANCHOR = "anchor",
      CONTROL_FLOW = "control flow", TRANSITION = "transition", USAGE = "usage", LINK = "link";

  // aggregation at the "from" end of an association (from = the whole)
  static final String AGG_NONE = "none", AGG_SHARED = "shared", AGG_COMPOSITE = "composite";

  static final class Param {
    String name, type, direction, defaultValue;
  }

  static final class Member {
    boolean method, isStatic, isAbstract;
    String visibility; // public, private, protected, package, or null
    String name, type, initial;
    final List<Param> params = new ArrayList<Param>();
  }

  static final class Node {
    String kind, name;
    final List<String> stereotypes = new ArrayList<String>();
    final List<Member> members = new ArrayList<Member>();
    final List<String> literals = new ArrayList<String>();
    boolean isAbstract, implicit;
    Node parent; // package or system that contains it
    String text; // note text
    Node(String kind, String name) { this.kind = kind; this.name = name; }
    /** Holds other elements on the diagram (a composite state only once something is put in it). */
    boolean container;
    String lane; // activity swimlane this node belongs to
    final List<String> slots = new ArrayList<String>(); // object diagram "name = value"
    boolean isContainer() { return container || PACKAGE.equals(kind) || SYSTEM.equals(kind) || LANE.equals(kind); }
    boolean isPseudo() {
      return INITIAL.equals(kind) || FINAL.equals(kind) || FLOW_FINAL.equals(kind) || DECISION.equals(kind) || MERGE.equals(kind)
          || FORK.equals(kind) || JOIN.equals(kind) || CHOICE.equals(kind) || HISTORY.equals(kind) || DEEP_HISTORY.equals(kind);
    }
    boolean isClassLike() { return CLASS.equals(kind) || INTERFACE.equals(kind) || ENUM.equals(kind) || ANNOTATION.equals(kind); }
    Member member(String n, boolean method) {
      for (Member m : members) if (m.method == method && m.name.equals(n)) return m;
      return null;
    }
  }

  static final class Link {
    String kind;
    Node from, to;
    String label, fromMult, toMult;
    String aggregation = AGG_NONE;
    boolean directed; // association drawn with an arrow head at "to"
    int line;
  }

  static final class Diagram {
    Kind kind;
    String title;
    boolean leftToRight;
    final Map<String, Node> byKey = new LinkedHashMap<String, Node>();
    final List<Node> nodes = new ArrayList<Node>();
    final List<Link> links = new ArrayList<Link>();
    final List<String> warnings = new ArrayList<String>();
    private int auto;

    Diagram(Kind kind) { this.kind = kind; }

    /** A new node that is never looked up by name (activity steps, pseudo states). */
    Node fresh(String kind, String name) {
      Node n = new Node(kind, name);
      nodes.add(n);
      byKey.put("\u0000" + (auto++), n);
      return n;
    }

    /** A node found only by its key, never by its (shared) display name, e.g. the start of one composite state. */
    Node keyed(String kind, String name, String key) {
      Node n = byKey.get(key);
      if (n == null) { n = new Node(kind, name); nodes.add(n); byKey.put(key, n); }
      return n;
    }

    Link link(String kind, Node from, Node to, String label) {
      Link k = new Link();
      k.kind = kind; k.from = from; k.to = to; k.label = label;
      links.add(k);
      return k;
    }

    Node node(String key) {
      if (key == null) return null;
      Node n = byKey.get(key);
      return n != null ? n : byKey.get(key.toLowerCase(Locale.ROOT));
    }

    /** Finds or adds a node. An implicit node (only seen in a line so far) takes the kind and name of a later declaration. */
    Node add(String kind, String name, String alias) {
      Node n = node(alias != null ? alias : name);
      if (n == null && alias != null) n = node(name);
      if (n != null) {
        if (n.implicit) {
          n.kind = kind;
          if (!name.equals(n.name)) { n.name = name; key(n, name); }
        }
        if (alias != null) key(n, alias);
        n.implicit = false;
        return n;
      }
      n = new Node(kind, name);
      nodes.add(n);
      key(n, name);
      if (alias != null) key(n, alias);
      return n;
    }

    private void key(Node n, String k) {
      byKey.put(k, n);
      if (!byKey.containsKey(k.toLowerCase(Locale.ROOT))) byKey.put(k.toLowerCase(Locale.ROOT), n);
    }

    int count(String kind) {
      int c = 0;
      for (Node n : nodes) if (n.kind.equals(kind)) c++;
      return c;
    }
  }
}
