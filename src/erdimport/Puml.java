package erdimport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The parsed content of one PlantUML ER diagram. Plain Java: no Visual Paradigm types. */
final class Puml {
  private Puml() {}

  static final String ONE = "ONE", ZERO_TO_ONE = "ZERO_TO_ONE", ZERO_TO_MANY = "ZERO_TO_MANY", ONE_TO_MANY = "ONE_TO_MANY";

  static boolean isMany(String m) { return ZERO_TO_MANY.equals(m) || ONE_TO_MANY.equals(m); }

  static final class Column {
    String name;
    String type;          // lower case type name, or null when the diagram gives none
    String typeText;      // as written, for messages
    int length, scale;
    boolean pk, fk, unique, mandatory;
    boolean explicitKeyTag; // PK or FK was written, so the "above the -- line" rule is not used
    boolean inKeySection;   // written above the first separator line
    boolean created;        // added by the importer for a relationship

    Column copyAs(String newName) {
      Column c = new Column();
      c.name = newName; c.type = type; c.typeText = typeText; c.length = length; c.scale = scale;
      return c;
    }
  }

  static final class Entity {
    String name;
    final List<Column> cols = new ArrayList<Column>();
    boolean sawSeparator;
    boolean implicit; // only named in a relationship line so far
    Integer x, y, w, h; // optional position from a "' vp-pos" line
    Entity(String name) { this.name = name; }

    Column col(String n) {
      for (Column c : cols) if (c.name.equalsIgnoreCase(n)) return c;
      return null;
    }

    List<Column> pks() {
      List<Column> out = new ArrayList<Column>();
      for (Column c : cols) if (c.pk) out.add(c);
      return out;
    }
  }

  static final class Rel {
    String left, right, label;
    String leftMult, rightMult;
    boolean dashed;
    int line;
    final List<String> fkCols = new ArrayList<String>(); // from "' vp-fk" lines
  }

  /** A relationship after deciding parent and child and which columns link them. */
  static final class Link {
    Entity parent, child;
    String fromMult, toMult, label;
    final List<String[]> pairs = new ArrayList<String[]>(); // {childColumn, parentColumn}
    boolean identifying;
  }

  static final class Diagram {
    String title;
    final Map<String, Entity> byKey = new LinkedHashMap<String, Entity>(); // alias and name -> entity
    final List<Entity> entities = new ArrayList<Entity>();
    final List<Rel> rels = new ArrayList<Rel>();
    final List<Link> links = new ArrayList<Link>();
    final List<String> warnings = new ArrayList<String>();

    Entity entity(String key) {
      Entity e = byKey.get(key);
      if (e == null) e = byKey.get(key.toLowerCase());
      return e;
    }

    Entity addEntity(String name, String alias) {
      Entity e = entity(alias != null ? alias : name);
      if (e != null) {
        if (e.implicit && alias != null && !name.equals(e.name)) { // "entity "Display" as A" after a line that used A
          e.name = name;
          byKey.put(name, e);
          byKey.put(name.toLowerCase(), e);
        }
        e.implicit = false;
        return e;
      }
      e = new Entity(name);
      entities.add(e);
      byKey.put(name, e);
      byKey.put(name.toLowerCase(), e);
      if (alias != null) { byKey.put(alias, e); byKey.put(alias.toLowerCase(), e); }
      return e;
    }
  }
}
