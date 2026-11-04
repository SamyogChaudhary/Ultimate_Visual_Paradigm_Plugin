package erdimport;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Writes parsed PlantUML ER diagrams into the open Visual Paradigm project. */
final class VpImporter {
  private static final String FK_TYPE = "com.vp.plugin.model.IDBForeignKey";
  private static final String COL_TYPE = "com.vp.plugin.model.IDBColumn";
  private static final String TABLE_TYPE = "com.vp.plugin.model.IDBTable";

  final List<String> warnings = new ArrayList<String>();
  final StringBuilder log = new StringBuilder();
  String step = "start";
  int newTables, reusedTables, newRels, reusedRels, diagrams, connectors;
  Object firstDiagram;
  private int biggest;

  private Object dm, factory, project;
  private final Map<String, Object> tablesByName = new HashMap<String, Object>(); // lower-case name -> IDBTable
  private final IdentityHashMap<Object, Boolean> createdTables = new IdentityHashMap<Object, Boolean>();
  private final java.util.Set<String> usedDiagramNames = new java.util.HashSet<String>();
  private final List<Object> createdFks = new ArrayList<Object>(), reusedFks = new ArrayList<Object>(), usedFks = new ArrayList<Object>();
  private final IdentityHashMap<Object, Boolean> createdHere = new IdentityHashMap<Object, Boolean>(); // tables made by the current diagram
  private final Map<Puml.Entity, List<String>> addedTo = new LinkedHashMap<Puml.Entity, List<String>>();
  private boolean diagramNamesLoaded;

  /** Type names to try, in order. The first one Visual Paradigm accepts is used. */
  private static final Map<String, String[]> TYPE_NAMES = new HashMap<String, String[]>();
  static {
    put(new String[] {"int", "integer"}, "int", "integer", "INT", "INTEGER");
    put(new String[] {"number", "numeric"}, "numeric", "decimal", "number", "int");
    put(new String[] {"bigint", "long"}, "bigint", "BIGINT", "integer");
    put(new String[] {"smallint", "short"}, "smallint", "SMALLINT", "integer");
    put(new String[] {"varchar", "string", "nvarchar"}, "varchar", "VARCHAR", "nvarchar");
    put(new String[] {"char"}, "char", "CHAR");
    put(new String[] {"text"}, "text", "TEXT", "varchar");
    put(new String[] {"decimal", "money", "currency"}, "decimal", "DECIMAL", "numeric");
    put(new String[] {"float", "double", "real"}, "float", "double", "real", "decimal");
    put(new String[] {"date"}, "date", "DATE");
    put(new String[] {"time"}, "time", "TIME");
    put(new String[] {"datetime", "datetime2", "timestamp"}, "datetime2", "datetime", "DATETIME", "timestamp");
    put(new String[] {"bit", "boolean", "bool"}, "bit", "BIT", "boolean", "BOOLEAN");
  }
  private static void put(String[] keys, String... names) { for (String k : keys) TYPE_NAMES.put(k, names); }

  VpImporter() throws Exception {
    step = "connect to Visual Paradigm";
    Object app = R.st("com.vp.plugin.ApplicationManager", "instance");
    dm = R.call(app, "getDiagramManager");
    factory = R.st("com.vp.plugin.model.factory.IModelElementFactory", "instance");
    project = R.tryCall(R.tryCall(app, "getProjectManager"), "getProject");
    loadExistingTables();
  }

  private void loadExistingTables() {
    step = "look for tables already in the project";
    if (project == null) return;
    Object type = R.fieldOr("com.vp.plugin.model.factory.IModelElementFactory", "MODEL_TYPE_DB_TABLE", "DBTable");
    Object arr = R.tryCall(project, "toAllLevelModelElementArray", type);
    if (arr == null) arr = R.tryCall(project, "toAllLevelModelElementArray");
    if (arr == null) return;
    Class<?> tableClass = null;
    try { tableClass = R.cls(TABLE_TYPE); } catch (Throwable ignore) { /* keep null */ }
    int n = java.lang.reflect.Array.getLength(arr);
    for (int i = 0; i < n; i++) {
      Object e = java.lang.reflect.Array.get(arr, i);
      if (e == null || (tableClass != null && !tableClass.isInstance(e))) continue;
      Object name = R.tryCall(e, "getName");
      if (name != null && !tablesByName.containsKey(name.toString().toLowerCase(Locale.ROOT))) {
        tablesByName.put(name.toString().toLowerCase(Locale.ROOT), e);
      }
    }
  }

  // ------------------------------------------------------------------ one diagram

  void importDiagram(Puml.Diagram d) throws Exception {
    for (String w : d.warnings) warn(d.title + ": " + w);
    Map<Puml.Entity, Object> tables = new LinkedHashMap<Puml.Entity, Object>();
    Map<String, Object> cols = new HashMap<String, Object>(); // "TABLE\u0000column" lower-case -> IDBColumn
    createdHere.clear();
    addedTo.clear();
    usedFks.clear();

    // tables and their ordinary columns (FK columns are added with their relationship)
    for (Puml.Entity e : d.entities) {
      step = "create table " + e.name;
      Object t = tablesByName.get(e.name.toLowerCase(Locale.ROOT));
      boolean isNew = t == null;
      if (isNew) {
        t = R.call(factory, "createDBTable");
        R.call(t, "setName", e.name);
        tablesByName.put(e.name.toLowerCase(Locale.ROOT), t);
        createdTables.put(t, Boolean.TRUE);
        createdHere.put(t, Boolean.TRUE);
        newTables++;
      } else if (!createdTables.containsKey(t)) {
        reusedTables++;
        createdTables.put(t, Boolean.FALSE);
      }
      tables.put(e, t);
      for (Puml.Column c : e.cols) {
        Object existing = isNew ? null : findColumn(t, c.name);
        if (existing != null) { cols.put(key(e, c.name), existing); continue; }
        if (isFkColumn(d, e, c.name)) continue;
        Object col = R.call(t, "createDBColumn");
        configure(e.name, col, c, true);
        cols.put(key(e, c.name), col);
        if (!isNew) noteAdded(e, c.name);
      }
      log("table " + e.name + (isNew ? "" : " (already in project)"));
    }

    // relationships
    Map<Puml.Link, Object> fks = new LinkedHashMap<Puml.Link, Object>();
    for (Puml.Link k : d.links) {
      step = "create relationship " + k.parent.name + " to " + k.child.name;
      Object fk = link(k, tables, cols);
      if (fk != null) fks.put(k, fk);
    }
    for (Puml.Entity e : d.entities) reorder(e, tables.get(e), cols);
    for (Map.Entry<Puml.Entity, List<String>> en : addedTo.entrySet()) {
      warn(en.getKey().name + " already existed, so it was kept and " + join(en.getValue()) + " added");
    }

    // diagram
    step = "create diagram " + d.title;
    String diaName = freeDiagramName(d.title);
    Object dia = R.call(dm, "createDiagram", R.field("com.vp.plugin.diagram.IDiagramTypeConstants", "DIAGRAM_TYPE_ER_DIAGRAM"));
    R.call(dia, "setName", diaName);
    usedDiagramNames.add(diaName.toLowerCase(Locale.ROOT));
    boolean anyPlaced = false;
    for (Puml.Entity e : d.entities) anyPlaced |= e.x != null;
    Map<Puml.Entity, Rectangle> start = anyPlaced ? placed(d) : grid(d.entities);
    Map<Puml.Entity, Object> shapes = new HashMap<Puml.Entity, Object>();
    Map<Puml.Entity, Rectangle> boxes = new HashMap<Puml.Entity, Rectangle>();
    for (Puml.Entity e : d.entities) {
      step = "place " + e.name + " on " + d.title;
      Rectangle r = start.get(e);
      Object shape = R.call(dm, "createDiagramElement", dia, tables.get(e));
      R.call(shape, "setBounds", Integer.valueOf(r.x), Integer.valueOf(r.y), Integer.valueOf(r.width), Integer.valueOf(r.height));
      R.tryCall(shape, "fitSize");
      R.tryCall(shape, "resetCaption");
      shapes.put(e, shape);
      boxes.put(e, bounds(shape, r));
    }
    Map<String, Integer> pairCount = new HashMap<String, Integer>();
    for (Map.Entry<Puml.Link, Object> en : fks.entrySet()) {
      Puml.Link k = en.getKey();
      step = "draw " + k.parent.name + " to " + k.child.name + " on " + d.title;
      String pair = k.parent.name + ">" + k.child.name;
      Integer n = pairCount.get(pair);
      pairCount.put(pair, Integer.valueOf(n == null ? 1 : n.intValue() + 1));
      Point[] pts = k.parent == k.child ? loop(boxes.get(k.parent), n == null ? 0 : n.intValue())
          : points(boxes.get(k.parent), boxes.get(k.child), n == null ? 0 : n.intValue());
      R.call(dm, "createConnector", dia, en.getValue(), shapes.get(k.parent), shapes.get(k.child), pts);
      connectors++;
    }
    step = "lay out " + d.title;
    if (anyPlaced) { // keep the file's arrangement; only tidy the lines
      Object route = R.fieldOr("com.vp.plugin.DiagramManager", "LAYOUT_ROUTE_CONNECTORS_ORTHOGONAL", Integer.valueOf(8));
      R.tryCall(dm, "layout", dia, route);
    } else {
      Object ortho = R.fieldOr("com.vp.plugin.DiagramManager", "LAYOUT_ORTHOGONAL", Integer.valueOf(4));
      if (R.tryCall(dm, "layout", dia, ortho) == null && !R.has(dm, "layout", 2)) {
        Object route = R.fieldOr("com.vp.plugin.DiagramManager", "LAYOUT_ROUTE_CONNECTORS_ORTHOGONAL", Integer.valueOf(8));
        R.tryCall(dm, "layout", dia, route);
      }
    }
    diagrams++;
    if (firstDiagram == null || d.entities.size() > biggest) { firstDiagram = dia; biggest = d.entities.size(); } // open the largest
    log("diagram " + diaName + ": " + d.entities.size() + " tables, " + fks.size() + " relationships");
  }

  void openFirst() throws Exception {
    if (firstDiagram != null) { step = "open the diagram"; R.call(dm, "openDiagram", firstDiagram); }
  }

  // ------------------------------------------------------------------ relationships

  private Object link(Puml.Link k, Map<Puml.Entity, Object> tables, Map<String, Object> cols) throws Exception {
    Object parent = tables.get(k.parent), child = tables.get(k.child);
    // reuse a relationship that is already in the project
    if (!k.pairs.isEmpty()) {
      Object childCol = cols.get(key(k.child, k.pairs.get(0)[0]));
      Object existing = existingFk(childCol, parent, child, k.label);
      if (existing != null) {
        // count only relationships that were in the project before this import
        if (!contains(createdFks.toArray(), existing) && !contains(reusedFks.toArray(), existing)) { reusedFks.add(existing); reusedRels++; }
        usedFks.add(existing);
        return existing;
      }
    }
    Object fk = R.call(factory, "createDBForeignKey");
    R.call(fk, "setFrom", parent);
    R.call(fk, "setTo", child);
    multiplicity(fk, "setFromMultiplicity", "FROM_MULTIPLICITY_" + k.fromMult);
    multiplicity(fk, "setToMultiplicity", "TO_MULTIPLICITY_" + k.toMult);
    if (k.identifying && !k.pairs.isEmpty()) R.call(fk, "setIdentifying", Boolean.TRUE);
    if (k.label != null && k.label.length() > 0) R.tryCall(fk, "setName", k.label);
    for (String[] p : k.pairs) {
      Object ref = cols.get(key(k.parent, p[1]));
      if (ref == null) ref = findColumn(parent, p[1]);
      if (ref == null) { warn(k.parent.name + "." + p[1] + " not found, so " + k.child.name + "." + p[0] + " is not linked"); continue; }
      Object[] before = columnArray(child);
      Object con = R.call(factory, "createDBForeignKeyConstraint");
      R.call(con, "setRefColumn", ref);
      R.call(con, "setForeignKey", fk);
      Object fkCol = cols.get(key(k.child, p[0]));
      Object auto = null;
      for (Object c : columnArray(child)) if (!contains(before, c)) { auto = c; break; }
      Puml.Column def = k.child.col(p[0]);
      if (fkCol == null && auto != null) {
        fkCol = auto; // Visual Paradigm made the column itself
        if (!createdHere.containsKey(child)) noteAdded(k.child, p[0]);
        configure(k.child.name, fkCol, def, !sameType(fkCol, ref));
      } else {
        if (fkCol == null) {
          fkCol = R.call(child, "createDBColumn");
          if (!createdHere.containsKey(child)) noteAdded(k.child, p[0]);
          configure(k.child.name, fkCol, def, true);
          if (def.type == null) copyType(fkCol, ref);
        }
        R.call(fkCol, "addForeignKeyConstraint", con);
        if (auto != null) R.tryCall(child, "removeDBColumn", auto); // the column already existed
      }
      cols.put(key(k.child, p[0]), fkCol);
    }
    newRels++;
    createdFks.add(fk);
    usedFks.add(fk);
    return fk;
  }

  /** The relationship already in the project that links this column to the parent table, if any. */
  private Object existingFk(Object childCol, Object parentTable, Object childTable, String label) {
    if (childCol == null) return null; // a new column cannot have a relationship yet
    Object arr = R.tryCall(childCol, "toForeignKeyConstraintArray");
    if (arr != null) {
      int n = java.lang.reflect.Array.getLength(arr);
      for (int i = 0; i < n; i++) {
        Object fk = R.tryCall(java.lang.reflect.Array.get(arr, i), "getForeignKey");
        if (fk != null && same(R.tryCall(fk, "getFrom"), parentTable) && !contains(usedFks.toArray(), fk)) return fk;
      }
      return null;
    }
    // older API without the column lookup: use the relationships that end at the child table
    Object rels = R.tryCall(childTable, "toToRelationshipArray");
    if (rels == null) return null;
    Class<?> fkClass;
    try { fkClass = R.cls(FK_TYPE); } catch (Throwable t) { return null; }
    Object first = null;
    int n = java.lang.reflect.Array.getLength(rels);
    for (int i = 0; i < n; i++) {
      Object fk = java.lang.reflect.Array.get(rels, i);
      if (!fkClass.isInstance(fk) || !same(R.tryCall(fk, "getFrom"), parentTable) || contains(usedFks.toArray(), fk)) continue;
      Object name = R.tryCall(fk, "getName");
      if (label != null && name != null && label.equals(name.toString())) return fk;
      if (first == null) first = fk;
    }
    return first;
  }

  private void noteAdded(Puml.Entity e, String col) {
    List<String> l = addedTo.get(e);
    if (l == null) { l = new ArrayList<String>(); addedTo.put(e, l); }
    if (!l.contains(col)) l.add(col);
  }

  private static String join(List<String> l) {
    StringBuilder b = new StringBuilder(l.size() == 1 ? "column " : "columns ");
    for (int i = 0; i < l.size(); i++) b.append(i == 0 ? "" : ", ").append(l.get(i));
    return b.append(l.size() == 1 ? " was" : " were").toString();
  }

  private static boolean isFkColumn(Puml.Diagram d, Puml.Entity e, String col) {
    for (Puml.Link k : d.links) if (k.child == e) for (String[] p : k.pairs) if (p[0].equalsIgnoreCase(col)) return true;
    return false;
  }

  private void multiplicity(Object fk, String setter, String constant) {
    Object value = R.fieldOr(FK_TYPE, constant, null);
    if (value == null) { warn("constant " + constant + " not found; VP default used"); return; }
    R.tryCall(fk, setter, value);
  }

  // ------------------------------------------------------------------ columns

  private void configure(String table, Object col, Puml.Column c, boolean setType) throws Exception {
    step = "set up column " + table + "." + c.name;
    R.call(col, "setName", c.name);
    if (setType && c.type != null) setType(table, col, c);
    if (c.pk) R.call(col, "setPrimaryKey", Boolean.TRUE);
    R.call(col, "setNullable", Boolean.valueOf(!c.mandatory && !c.pk));
    if (c.unique && !c.pk) R.tryCall(col, "setUnique", Boolean.TRUE);
  }

  private void setType(String table, Object col, Puml.Column c) throws Exception {
    String[] names = TYPE_NAMES.get(c.type);
    if (names == null) names = new String[] {c.type, c.type.toUpperCase(Locale.ROOT)};
    for (String n : names) {
      Object ok = R.call(col, "setType", n, Integer.valueOf(c.length), Integer.valueOf(c.scale));
      if (!Boolean.FALSE.equals(ok)) {
        if (!n.equalsIgnoreCase(c.type)) warn(table + "." + c.name + ": type " + c.typeText + " shown as " + n.toUpperCase(Locale.ROOT));
        return;
      }
    }
    R.tryCall(col, "setUserType", c.typeText);
    warn(table + "." + c.name + ": type " + c.typeText + " kept as a user type");
  }

  private static void copyType(Object col, Object ref) {
    Object name = R.tryCall(ref, "getTypeName");
    Object len = R.tryCall(ref, "getLength"), scale = R.tryCall(ref, "getScale");
    if (name instanceof String) R.tryCall(col, "setType", name, len instanceof Integer ? len : Integer.valueOf(0), scale instanceof Integer ? scale : Integer.valueOf(0));
  }

  private static boolean sameType(Object a, Object b) {
    Object ta = R.tryCall(a, "getTypeInText"), tb = R.tryCall(b, "getTypeInText");
    return ta != null && ta.equals(tb);
  }

  private void reorder(Puml.Entity e, Object table, Map<String, Object> cols) {
    if (!createdHere.containsKey(table)) return; // leave tables that were already there as they are
    try {
      List<Object> ordered = new ArrayList<Object>();
      for (Puml.Column c : e.cols) {
        Object col = cols.get(key(e, c.name));
        if (col != null && !contains(ordered.toArray(), col)) ordered.add(col);
      }
      for (Object c : columnArray(table)) if (!contains(ordered.toArray(), c)) ordered.add(c);
      R.call(table, "reorderDBColumn", new Object[] {R.typedArray(COL_TYPE, ordered)});
    } catch (Throwable t) {
      warn("could not reorder the columns of " + e.name);
    }
  }

  private Object[] columnArray(Object table) throws Exception {
    Object arr = R.call(table, "toDBColumnArray");
    if (arr == null) return new Object[0];
    int n = java.lang.reflect.Array.getLength(arr);
    Object[] out = new Object[n];
    for (int i = 0; i < n; i++) out[i] = java.lang.reflect.Array.get(arr, i);
    return out;
  }

  private static boolean contains(Object[] arr, Object o) {
    for (Object x : arr) if (same(x, o)) return true;
    return false;
  }

  /** Same model element: the same object, equal, or the same Visual Paradigm id. */
  static boolean same(Object a, Object b) {
    if (a == b) return true;
    if (a == null || b == null) return false;
    if (a.equals(b)) return true;
    Object ia = R.tryCall(a, "getId"), ib = R.tryCall(b, "getId");
    return ia != null && ia.equals(ib);
  }

  /** Finds a column by name (any case); VP's own lookup first, then a scan. */
  private Object findColumn(Object table, String name) {
    Object c = R.tryCall(table, "getDBColumnByName", name);
    if (c != null) return c;
    Object arr = R.tryCall(table, "toDBColumnArray");
    if (arr == null) return null;
    int n = java.lang.reflect.Array.getLength(arr);
    for (int i = 0; i < n; i++) {
      Object col = java.lang.reflect.Array.get(arr, i);
      Object cn = R.tryCall(col, "getName");
      if (cn != null && cn.toString().equalsIgnoreCase(name)) return col;
    }
    return null;
  }

  /** The title, or the title with (2), (3)... when the project already has a diagram with that name. */
  private String freeDiagramName(String title) {
    if (!diagramNamesLoaded) {
      diagramNamesLoaded = true;
      Object arr = R.tryCall(project, "toDiagramArray");
      if (arr == null) arr = R.tryCall(dm, "getAllDiagrams");
      if (arr != null) {
        int n = java.lang.reflect.Array.getLength(arr);
        for (int i = 0; i < n; i++) {
          Object name = R.tryCall(java.lang.reflect.Array.get(arr, i), "getName");
          if (name != null) usedDiagramNames.add(name.toString().toLowerCase(Locale.ROOT));
        }
      }
    }
    String name = title;
    for (int i = 2; usedDiagramNames.contains(name.toLowerCase(Locale.ROOT)); i++) name = title + " (" + i + ")";
    return name;
  }

  private static String key(Puml.Entity e, String col) { return (e.name + "\u0000" + col).toLowerCase(Locale.ROOT); }

  // ------------------------------------------------------------------ layout helpers

  static int estW(Puml.Entity e) {
    int chars = e.name.length();
    for (Puml.Column c : e.cols) chars = Math.max(chars, c.name.length() + (c.typeText == null ? 0 : c.typeText.length()) + 4);
    return Math.max(170, (int) (chars * 7.2) + 60);
  }

  static int estH(Puml.Entity e) { return 30 + 19 * Math.max(1, e.cols.size()); }

  /** Positions from the file's ' vp-pos lines. Tables without one (such as a link table the importer added)
   *  go between the tables they connect to, or below the others, without overlapping. */
  static Map<Puml.Entity, Rectangle> placed(Puml.Diagram d) {
    Map<Puml.Entity, Rectangle> out = new LinkedHashMap<Puml.Entity, Rectangle>();
    int minX = Integer.MAX_VALUE, maxY = 0;
    for (Puml.Entity e : d.entities) {
      if (e.x == null) continue;
      Rectangle r = new Rectangle(e.x.intValue(), e.y.intValue(), e.w != null ? e.w.intValue() : estW(e), e.h != null ? e.h.intValue() : estH(e));
      out.put(e, r);
      minX = Math.min(minX, r.x);
      maxY = Math.max(maxY, r.y + r.height);
    }
    for (Puml.Entity e : d.entities) {
      if (e.x != null) continue;
      Rectangle r = new Rectangle(0, 0, estW(e), estH(e));
      double cx = 0, cy = 0;
      int n = 0;
      for (Puml.Link k : d.links) {
        Puml.Entity other = k.child == e ? k.parent : k.parent == e ? k.child : null;
        Rectangle o = other == null ? null : out.get(other);
        if (o != null && other != e) { cx += o.getCenterX(); cy += o.getCenterY(); n++; }
      }
      if (n > 0) { r.x = (int) (cx / n - r.width / 2.0); r.y = (int) (cy / n - r.height / 2.0); }
      else { r.x = minX; r.y = maxY + 90; }
      for (int i = 0; i < 400 && hits(r, out.values()); i++) r.y += 30;
      out.put(e, r);
      maxY = Math.max(maxY, r.y + r.height);
    }
    return out;
  }

  private static boolean hits(Rectangle r, java.util.Collection<Rectangle> others) {
    Rectangle grown = new Rectangle(r.x - 30, r.y - 30, r.width + 60, r.height + 60);
    for (Rectangle o : others) if (grown.intersects(o)) return true;
    return false;
  }

  /** Simple grid used when the file has no positions; VP's own layout then tidies it. */
  static Map<Puml.Entity, Rectangle> grid(List<Puml.Entity> es) {
    Map<Puml.Entity, Rectangle> out = new HashMap<Puml.Entity, Rectangle>();
    int per = Math.max(1, (int) Math.ceil(Math.sqrt(es.size())));
    int y = 40;
    for (int row = 0; row * per < es.size(); row++) {
      int x = 40, rowH = 0;
      for (int i = row * per; i < Math.min(es.size(), (row + 1) * per); i++) {
        Puml.Entity e = es.get(i);
        int w = estW(e), h = estH(e);
        out.put(e, new Rectangle(x, y, w, h));
        x += w + 90;
        rowH = Math.max(rowH, h);
      }
      y += rowH + 90;
    }
    return out;
  }

  private static Rectangle bounds(Object shape, Rectangle fallback) {
    int x = num(R.tryCall(shape, "getX"), fallback.x), y = num(R.tryCall(shape, "getY"), fallback.y);
    int w = num(R.tryCall(shape, "getWidth"), fallback.width), h = num(R.tryCall(shape, "getHeight"), fallback.height);
    return new Rectangle(x, y, w > 0 ? w : fallback.width, h > 0 ? h : fallback.height);
  }

  private static int num(Object o, int fallback) { return o instanceof Number ? ((Number) o).intValue() : fallback; }

  static Point[] points(Rectangle a, Rectangle b, int offsetIndex) {
    double ax = a.getCenterX(), ay = a.getCenterY(), bx = b.getCenterX(), by = b.getCenterY();
    double dx = bx - ax, dy = by - ay, len = Math.max(1, Math.hypot(dx, dy));
    double off = offsetIndex * 18.0;
    double ox = -dy / len * off, oy = dx / len * off;
    return new Point[] {clip(a, ax + ox, ay + oy, dx, dy), clip(b, bx + ox, by + oy, -dx, -dy)};
  }

  /** A line that leaves the right side of a table and comes back in at its top (table linked to itself). */
  static Point[] loop(Rectangle r, int offsetIndex) {
    int o = 24 + offsetIndex * 16;
    int sy = r.y + Math.min(r.height / 2, 20 + offsetIndex * 12), ex = r.x + r.width - Math.min(r.width / 3, 30 + offsetIndex * 16);
    return new Point[] {new Point(r.x + r.width, sy), new Point(r.x + r.width + o, sy), new Point(r.x + r.width + o, r.y - o),
        new Point(ex, r.y - o), new Point(ex, r.y)};
  }

  private static Point clip(Rectangle r, double cx, double cy, double dx, double dy) {
    double tx = dx == 0 ? Double.MAX_VALUE : (r.width / 2.0) / Math.abs(dx);
    double ty = dy == 0 ? Double.MAX_VALUE : (r.height / 2.0) / Math.abs(dy);
    double t = Math.min(tx, ty);
    if (t == Double.MAX_VALUE) t = 0;
    return new Point((int) Math.round(cx + dx * t), (int) Math.round(cy + dy * t));
  }

  private void warn(String s) { if (!warnings.contains(s)) warnings.add(s); }

  private void log(String s) { log.append(s).append('\n'); }
}
