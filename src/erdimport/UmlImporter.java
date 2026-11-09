package erdimport;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Writes parsed PlantUML class and use case diagrams into the open Visual Paradigm project. */
final class UmlImporter {
  private static final String F = "com.vp.plugin.model.factory.IModelElementFactory";

  final List<String> warnings = new ArrayList<String>();
  final StringBuilder log = new StringBuilder();
  String step = "start";
  int diagrams, created, reused, relationships;
  Object firstDiagram;
  private int biggest;

  private final Object dm, factory, project;
  /** "modelType\u0000lower-case name" -> model element already in the project or made by this import. */
  private final Map<String, Object> existing = new HashMap<String, Object>();
  private final java.util.Set<String> loadedTypes = new java.util.HashSet<String>();
  private final java.util.Set<String> usedDiagramNames = new java.util.HashSet<String>();
  private boolean diagramNamesLoaded;

  UmlImporter() throws Exception {
    step = "connect to Visual Paradigm";
    Object app = R.st("com.vp.plugin.ApplicationManager", "instance");
    dm = R.call(app, "getDiagramManager");
    factory = R.st(F, "instance");
    project = R.tryCall(R.tryCall(app, "getProjectManager"), "getProject");
  }

  // ------------------------------------------------------------------ one diagram

  void importDiagram(Uml.Diagram d) throws Exception {
    for (String w : d.warnings) warn(d.title + ": " + w);
    boolean uc = d.kind == Uml.Kind.USECASE;
    boolean flow = d.kind == Uml.Kind.ACTIVITY || d.kind == Uml.Kind.STATE;
    kind = d.kind;
    regions.clear();
    Map<Uml.Node, Object> models = new LinkedHashMap<Uml.Node, Object>();
    IdentityHashMap<Object, Boolean> fresh = new IdentityHashMap<Object, Boolean>();

    // model elements, containers first so children can be put inside them
    List<Uml.Node> order = new ArrayList<Uml.Node>(d.nodes);
    Collections.sort(order, new Comparator<Uml.Node>() {
      public int compare(Uml.Node a, Uml.Node b) { return depth(a) - depth(b); }
    });
    for (Uml.Node n : order) {
      step = "create " + n.kind + " " + n.name;
      Object m = model(n, fresh);
      models.put(n, m);
      if (fresh.containsKey(m) && n.parent != null && models.get(n.parent) != null && !Uml.NOTE.equals(n.kind)) {
        Object owner = models.get(n.parent);
        if (Uml.STATE.equals(n.parent.kind)) owner = region(owner); // sub-states live in a region of their state
        R.tryCall(owner, "addChild", m);
      }
    }

    // relationships
    Map<Uml.Link, Object> rels = new LinkedHashMap<Uml.Link, Object>();
    for (Uml.Link k : d.links) {
      step = "create " + k.kind + " " + k.from.name + " to " + k.to.name;
      try {
        Object rel = relationship(k, models.get(k.from), models.get(k.to));
        if (rel != null) rels.put(k, rel);
      } catch (Throwable t) {
        Throwable c = t.getCause() != null ? t.getCause() : t;
        warn(d.title + ": the " + k.kind + " from " + label(k.from) + " to " + label(k.to) + " was skipped (" + c.getMessage() + ")");
      }
    }

    // diagram
    step = "create diagram " + d.title;
    String type = DIAGRAM_TYPES.get(d.kind);
    Object dia = R.call(dm, "createDiagram", R.field("com.vp.plugin.diagram.IDiagramTypeConstants", type));
    String diaName = freeDiagramName(d.title);
    R.call(dia, "setName", diaName);
    usedDiagramNames.add(diaName.toLowerCase(Locale.ROOT));

    Map<Uml.Node, Rectangle> boxes = uc ? Layout.useCase(d) : flow ? Layout.flow(d) : Layout.classes(d);
    Map<Uml.Node, Object> shapes = new HashMap<Uml.Node, Object>();
    for (Uml.Node n : order) { // containers first, so their shapes exist when children are added
      step = "place " + n.name + " on " + d.title;
      Rectangle r = boxes.get(n);
      Object shape = R.call(dm, "createDiagramElement", dia, models.get(n));
      R.call(shape, "setBounds", Integer.valueOf(r.x), Integer.valueOf(r.y), Integer.valueOf(r.width), Integer.valueOf(r.height));
      if (n.parent != null && shapes.get(n.parent) != null) R.tryCall(shapes.get(n.parent), "addChild", shape);
      if (n.lane != null && laneShape(d, n, shapes) != null) R.tryCall(laneShape(d, n, shapes), "addChild", shape);
      if (!n.isContainer() && (!flow || Uml.NOTE.equals(n.kind))) R.tryCall(shape, "fitSize");
      // without these, actor names are not drawn (the caption is left at size 0)
      R.tryCall(shape, "setRequestResetCaption", Boolean.TRUE);
      R.tryCall(shape, "setRequestResetCaptionSize", Boolean.TRUE);
      shapes.put(n, shape);
    }
    for (Uml.Node n : order) { // classes may have grown when fitted; read back the real sizes
      Object s = shapes.get(n);
      boxes.put(n, bounds(s, boxes.get(n)));
    }

    Map<String, Integer> pairCount = new HashMap<String, Integer>();
    for (Map.Entry<Uml.Link, Object> en : rels.entrySet()) {
      Uml.Link k = en.getKey();
      step = "draw " + k.kind + " " + k.from.name + " to " + k.to.name;
      String pair = k.from.name + ">" + k.to.name;
      Integer c = pairCount.get(pair);
      int idx = c == null ? 0 : c.intValue();
      pairCount.put(pair, Integer.valueOf(idx + 1));
      Rectangle a = boxes.get(k.from), b = boxes.get(k.to);
      Point[] pts = k.from == k.to ? VpImporter.loop(a, idx)
          : flow && b.y + b.height <= a.y ? backEdge(a, b, boxes.values(), idx)
          : uc && Uml.ACTOR.equals(k.from.kind) && Uml.ACTOR.equals(k.to.kind) && Math.abs(a.x - b.x) < 30 ? sideways(a, b)
          : VpImporter.points(a, b, idx);
      Object con = R.tryCall(dm, "createConnector", dia, en.getValue(), shapes.get(k.from), shapes.get(k.to), pts);
      if (con == null) warn(d.title + ": could not draw the " + k.kind + " from " + label(k.from) + " to " + label(k.to));
      else if (k.label != null) R.tryCall(con, "setRequestResetCaption", Boolean.TRUE); // otherwise some line labels stay hidden
    }

    step = "lay out " + d.title;
    boolean containers = false;
    for (Uml.Node n : d.nodes) containers |= n.isContainer();
    if (flow) {
      // keep the layered layout; it already follows the flow
    } else if (!uc && !containers && d.nodes.size() > 1) {
      Object hier = R.fieldOr("com.vp.plugin.DiagramManager", "LAYOUT_HIERARCHIC", Integer.valueOf(3));
      R.tryCall(dm, "layout", dia, hier);
    } else if (!uc && !rels.isEmpty()) { // keep the packages as placed, but route the lines around the shapes
      Object route = R.fieldOr("com.vp.plugin.DiagramManager", "LAYOUT_ROUTE_CONNECTORS_ORTHOGONAL", Integer.valueOf(8));
      R.tryCall(dm, "layout", dia, route);
    }
    diagrams++;
    if (firstDiagram == null || d.nodes.size() > biggest) { firstDiagram = dia; biggest = d.nodes.size(); }
    log("diagram " + diaName + " (" + d.kind.label + "): " + d.nodes.size() + " elements, " + rels.size() + " relationships");
  }

  void openFirst() throws Exception {
    if (firstDiagram != null) { step = "open the diagram"; R.call(dm, "openDiagram", firstDiagram); }
  }

  private static int depth(Uml.Node n) {
    int d = 0;
    for (Uml.Node p = n.parent; p != null; p = p.parent) d++;
    return n.isContainer() ? d : d + 1000;
  }

  // ------------------------------------------------------------------ model elements

  private Object model(Uml.Node n, IdentityHashMap<Object, Boolean> fresh) throws Exception {
    String typeConst = n.isClassLike() ? "MODEL_TYPE_CLASS" : Uml.ACTOR.equals(n.kind) ? "MODEL_TYPE_ACTOR"
        : Uml.USECASE.equals(n.kind) ? "MODEL_TYPE_USE_CASE" : Uml.PACKAGE.equals(n.kind) ? "MODEL_TYPE_PACKAGE"
        : Uml.SYSTEM.equals(n.kind) ? "MODEL_TYPE_SYSTEM" : Uml.COMPONENT.equals(n.kind) ? "MODEL_TYPE_COMPONENT"
        : Uml.NODE.equals(n.kind) ? "MODEL_TYPE_NODE" : Uml.ARTIFACT.equals(n.kind) ? "MODEL_TYPE_ARTIFACT" : null;
    if (typeConst != null) {
      Object found = find(typeConst, n.name);
      if (found != null) {
        if (n.isClassLike()) addMissingMembers(n, found);
        reused++;
        log(n.kind + " " + n.name + " (already in project)");
        return found;
      }
    }
    Object m;
    if (n.isClassLike()) {
      m = R.call(factory, "createClass");
      R.call(m, "setName", n.name);
      if (Uml.INTERFACE.equals(n.kind)) R.tryCall(m, "addStereotype", "Interface");
      if (Uml.ENUM.equals(n.kind)) R.tryCall(m, "addStereotype", "enumeration");
      if (Uml.ANNOTATION.equals(n.kind)) R.tryCall(m, "addStereotype", "annotation");
      if (n.isAbstract) R.tryCall(m, "setAbstract", Boolean.TRUE);
      for (String s : n.stereotypes) R.tryCall(m, "addStereotype", s);
      addMissingMembers(n, m);
    } else if (Uml.ACTOR.equals(n.kind)) {
      m = R.call(factory, "createActor");
    } else if (Uml.USECASE.equals(n.kind)) {
      m = R.call(factory, "createUseCase");
    } else if (Uml.PACKAGE.equals(n.kind)) {
      m = R.call(factory, "createPackage");
    } else if (Uml.SYSTEM.equals(n.kind)) {
      m = R.call(factory, "createSystem");
    } else if (Uml.NOTE.equals(n.kind)) {
      m = R.call(factory, "createNOTE");
      R.tryCall(m, "setDescription", n.text == null ? "" : n.text);
    } else if (Uml.OBJECT.equals(n.kind)) {
      m = object(n);
    } else {
      String create = factoryMethod(n.kind);
      if (create == null) throw new IllegalStateException("no Visual Paradigm element for " + n.kind);
      m = R.call(factory, create);
      if (Uml.PORT_INTERFACE.equals(n.kind)) R.tryCall(m, "addStereotype", "Interface");
      if (!Uml.STATE.equals(n.kind) && n.text != null) R.tryCall(m, "setDocumentation", n.text);
      if (Uml.STATE.equals(n.kind) && n.text != null) stateText(n, m);
    }
    if (!n.isClassLike()) {
      if (!Uml.NOTE.equals(n.kind) && !Uml.OBJECT.equals(n.kind)) R.call(m, "setName", n.name);
      for (String s : n.stereotypes) R.tryCall(m, "addStereotype", s);
    }
    if (typeConst != null) existing.put(R.fieldOr(F, typeConst, typeConst) + "\u0000" + n.name.toLowerCase(Locale.ROOT), m);
    fresh.put(m, Boolean.TRUE);
    created++;
    log(n.kind + " " + n.name);
    return m;
  }

  private void addMissingMembers(Uml.Node n, Object cls) throws Exception {
    for (String lit : n.literals) {
      if (R.tryCall(cls, "getEnumerationLiteralByName", lit) != null) continue;
      step = "add literal " + n.name + "." + lit;
      Object l = R.call(factory, "createEnumerationLiteral");
      R.call(l, "setName", lit);
      R.call(cls, "addEnumerationLiteral", l);
    }
    for (Uml.Member mb : n.members) {
      step = "add " + (mb.method ? "operation " : "attribute ") + n.name + "." + mb.name;
      if (!mb.method && R.tryCall(cls, "getAttributeByName", mb.name) != null) continue;
      if (mb.method && hasOperation(cls, mb)) continue;
      Object m = R.call(cls, mb.method ? "createOperation" : "createAttribute");
      R.call(m, "setName", mb.name);
      if (mb.visibility != null) R.tryCall(m, "setVisibility", mb.visibility);
      if (mb.isStatic) R.tryCall(m, "setScope", "classifier");
      if (mb.isAbstract) R.tryCall(m, "setAbstract", Boolean.TRUE);
      if (mb.method) {
        if (mb.type != null) R.tryCall(m, "setReturnType", mb.type);
        for (Uml.Param p : mb.params) {
          Object pr = R.call(m, "createParameter");
          R.call(pr, "setName", p.name);
          if (p.type != null) R.tryCall(pr, "setType", p.type);
          if (p.direction != null) R.tryCall(pr, "setDirection", p.direction);
          if (p.defaultValue != null) R.tryCall(pr, "setDefaultValue", p.defaultValue);
        }
      } else {
        if (mb.type != null) R.tryCall(m, "setType", mb.type);
        if (mb.initial != null) R.tryCall(m, "setInitialValue", mb.initial);
      }
    }
  }

  /** Same name and the same number of parameters. */
  private static boolean hasOperation(Object cls, Uml.Member mb) {
    Object arr = R.tryCall(cls, "toOperationArray");
    if (arr == null) return false;
    for (int i = 0; i < java.lang.reflect.Array.getLength(arr); i++) {
      Object op = java.lang.reflect.Array.get(arr, i);
      if (!mb.name.equals(R.tryCall(op, "getName"))) continue;
      Object ps = R.tryCall(op, "toParameterArray");
      int n = ps == null ? 0 : java.lang.reflect.Array.getLength(ps);
      if (n == mb.params.size()) return true;
    }
    return false;
  }

  /** A model element of this type and name that is already in the project (or was made earlier in this import). */
  private Object find(String typeConst, String name) {
    Object type = R.fieldOr(F, typeConst, null);
    if (type == null) return null;
    if (loadedTypes.add(type.toString()) && project != null) {
      Object arr = R.tryCall(project, "toAllLevelModelElementArray", type);
      if (arr != null) {
        for (int i = 0; i < java.lang.reflect.Array.getLength(arr); i++) {
          Object e = java.lang.reflect.Array.get(arr, i);
          Object n = R.tryCall(e, "getName");
          String k = type + "\u0000" + (n == null ? "" : n.toString().toLowerCase(Locale.ROOT));
          if (n != null && !existing.containsKey(k)) existing.put(k, e);
        }
      }
    }
    return existing.get(type + "\u0000" + name.toLowerCase(Locale.ROOT));
  }

  // ------------------------------------------------------------------ relationships

  private Object relationship(Uml.Link k, Object from, Object to) throws Exception {
    String kind = k.kind;
    Object rel;
    if (Uml.ASSOCIATION.equals(kind)) {
      rel = R.call(factory, "createAssociation");
      R.call(rel, "setFrom", from);
      R.call(rel, "setTo", to);
      Object fe = R.tryCall(rel, "getFromEnd"), te = R.tryCall(rel, "getToEnd");
      if (k.fromMult != null) R.tryCall(fe, "setMultiplicity", k.fromMult);
      if (k.toMult != null) R.tryCall(te, "setMultiplicity", k.toMult);
      if (Uml.AGG_COMPOSITE.equals(k.aggregation)) R.tryCall(fe, "setAggregationKind", R.fieldOr("com.vp.plugin.model.IAssociationEnd", "AGGREGATION_KIND_COMPOSITED", "Composited"));
      if (Uml.AGG_SHARED.equals(k.aggregation)) R.tryCall(fe, "setAggregationKind", R.fieldOr("com.vp.plugin.model.IAssociationEnd", "AGGREGATION_KIND_SHARED", "Aggregation"));
      if (k.directed) { // arrow head at "to" only; the other end left unspecified (no X mark)
        R.tryCall(te, "setNavigable", R.fieldOr("com.vp.plugin.model.IAssociationEnd", "NAVIGABLE_NAVIGABLE", Integer.valueOf(0)));
        R.tryCall(fe, "setNavigable", R.fieldOr("com.vp.plugin.model.IAssociationEnd", "NAVIGABLE_UNSPECIFIED", Integer.valueOf(1)));
      }
    } else {
      String create = Uml.GENERALIZATION.equals(kind) ? "createGeneralization" : Uml.REALIZATION.equals(kind) ? "createRealization"
          : Uml.INCLUDE.equals(kind) ? "createInclude" : Uml.EXTEND.equals(kind) ? "createExtend"
          : Uml.ANCHOR.equals(kind) ? "createAnchor" : Uml.CONTROL_FLOW.equals(kind) ? "createControlFlow"
          : Uml.TRANSITION.equals(kind) ? "createTransition2" : Uml.USAGE.equals(kind) ? "createUsage"
          : Uml.LINK.equals(kind) ? "createLink" : "createDependency";
      rel = R.call(factory, create);
      R.call(rel, "setFrom", from);
      R.call(rel, "setTo", to);
      if (Uml.EXTEND.equals(kind)) { // VP adds an extension point called "ExtensionPoint" to the base use case
        Object ep = R.tryCall(rel, "getExtensionPoint");
        if (ep != null) R.tryCall(ep, "setName", k.label != null ? k.label : k.to.name);
      }
    }
    if (k.label != null) {
      R.tryCall(rel, "setName", k.label);
    }
    relationships++;
    return rel;
  }

  // ------------------------------------------------------------------ activity, state, object helpers

  private static final Map<Uml.Kind, String> DIAGRAM_TYPES = new HashMap<Uml.Kind, String>();
  static {
    DIAGRAM_TYPES.put(Uml.Kind.CLASS, "DIAGRAM_TYPE_CLASS_DIAGRAM");
    DIAGRAM_TYPES.put(Uml.Kind.USECASE, "DIAGRAM_TYPE_USE_CASE_DIAGRAM");
    DIAGRAM_TYPES.put(Uml.Kind.ACTIVITY, "DIAGRAM_TYPE_ACTIVITY_DIAGRAM");
    DIAGRAM_TYPES.put(Uml.Kind.STATE, "DIAGRAM_TYPE_STATE_DIAGRAM");
    DIAGRAM_TYPES.put(Uml.Kind.COMPONENT, "DIAGRAM_TYPE_COMPONENT_DIAGRAM");
    DIAGRAM_TYPES.put(Uml.Kind.DEPLOYMENT, "DIAGRAM_TYPE_DEPLOYMENT_DIAGRAM");
    DIAGRAM_TYPES.put(Uml.Kind.OBJECT, "DIAGRAM_TYPE_OBJECT_DIAGRAM");
  }

  private Uml.Kind kind;
  private final Map<Object, Object> regions = new IdentityHashMap<Object, Object>();

  /** The factory method for the node kind; activity and state machines use different pseudo states. */
  private String factoryMethod(String k) {
    boolean state = kind == Uml.Kind.STATE;
    if (Uml.ACTION.equals(k)) return "createActivityAction";
    if (Uml.INITIAL.equals(k)) return state ? "createInitialPseudoState" : "createInitialNode";
    if (Uml.FINAL.equals(k)) return state ? "createFinalState2" : "createActivityFinalNode";
    if (Uml.FLOW_FINAL.equals(k)) return "createFlowFinalNode";
    if (Uml.DECISION.equals(k)) return "createDecisionNode";
    if (Uml.MERGE.equals(k)) return "createMergeNode";
    if (Uml.FORK.equals(k)) return state ? "createFork" : "createForkNode";
    if (Uml.JOIN.equals(k)) return state ? "createJoin" : "createJoinNode";
    if (Uml.CHOICE.equals(k)) return "createChoice";
    if (Uml.HISTORY.equals(k)) return "createShallowHistory";
    if (Uml.DEEP_HISTORY.equals(k)) return "createDeepHistory";
    if (Uml.STATE.equals(k)) return "createState2";
    if (Uml.LANE.equals(k)) return "createActivityPartition";
    if (Uml.COMPONENT.equals(k)) return "createComponent";
    if (Uml.PORT_INTERFACE.equals(k)) return "createClass"; // VP only links usages and realizations to class interfaces
    if (Uml.NODE.equals(k)) return "createNode";
    if (Uml.ARTIFACT.equals(k)) return "createArtifact";
    return null;
  }

  private Object region(Object state) {
    Object r = regions.get(state);
    if (r == null) {
      r = R.tryCall(state, "createRegion");
      if (r == null) return state;
      regions.put(state, r);
    }
    return r;
  }

  /** entry / exit / do lines become the state's internal activities; anything else its documentation. */
  private void stateText(Uml.Node n, Object st) {
    StringBuilder other = new StringBuilder();
    for (String line : n.text.split("\n")) {
      java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(?i)(entry|exit|do)\\s*/\\s*(.*)$").matcher(line.trim());
      Object act = null;
      if (m.matches()) {
        act = R.tryCall(factory, "createActivity");
        if (act != null) {
          R.tryCall(act, "setName", m.group(2).trim());
          String w = m.group(1).toLowerCase(Locale.ROOT);
          Object ok = R.tryCall(st, w.equals("entry") ? "setEntry" : w.equals("exit") ? "setExit" : "setDoActivity", act);
          if (ok == null && !R.has(st, "setEntry", 1)) act = null;
        }
      }
      if (act == null) other.append(line).append('\n');
    }
    if (other.length() > 0) R.tryCall(st, "setDocumentation", other.toString().trim());
  }

  /** An instance specification. "name : Class" sets the classifier; "field = value" lines become slots. */
  private Object object(Uml.Node n) throws Exception {
    Object inst = R.call(factory, "createInstanceSpecification");
    String name = n.name, cls = null;
    int c = name.indexOf(" : ");
    if (c >= 0) { cls = name.substring(c + 3).trim(); name = name.substring(0, c).trim(); }
    R.call(inst, "setName", name);
    Object classifier = null;
    Object holder = null;
    if ((cls == null || cls.length() == 0) && !n.slots.isEmpty()) {
      // VP names a slot after an attribute; an untyped object's fields are kept on a class that is not its type
      holder = R.call(factory, "createClass");
      R.call(holder, "setName", name + " fields");
      Object pkg = fieldsPackage();
      if (pkg != null) R.tryCall(pkg, "addChild", holder);
    } else if (cls != null && cls.length() > 0) {
      classifier = find("MODEL_TYPE_CLASS", cls);
      if (classifier == null) {
        classifier = R.call(factory, "createClass");
        R.call(classifier, "setName", cls);
        existing.put(R.fieldOr(F, "MODEL_TYPE_CLASS", "Class") + "\u0000" + cls.toLowerCase(Locale.ROOT), classifier);
      }
      R.tryCall(inst, "addClassifier", classifier);
    }
    for (String slot : n.slots) {
      int eq = slot.indexOf(" = ");
      String field = eq < 0 ? slot : slot.substring(0, eq).trim(), value = eq < 0 ? "" : slot.substring(eq + 3).trim();
      Object owner = classifier != null ? classifier : holder;
      Object attr = owner == null ? null : R.tryCall(owner, "getAttributeByName", field);
      if (attr == null && owner != null) {
        attr = R.call(owner, "createAttribute");
        R.call(attr, "setName", field);
      }
      Object sl = R.call(factory, "createSlot");
      if (attr != null) R.tryCall(sl, "setFeature", attr);
      else R.tryCall(sl, "setName", field);
      Object v = R.call(factory, "createCompositeValueSpecification");
      R.tryCall(v, "setValue", value);
      R.tryCall(sl, "addValue", v);
      R.tryCall(inst, "addSlot", sl);
    }
    return inst;
  }

  /** Between two actors in one column: out of the side of the figures, so the arrow head is not hidden by the name. */
  private static Point[] sideways(Rectangle a, Rectangle b) {
    int x = Math.max(a.x + a.width, b.x + b.width) + 25;
    int ay = a.y + a.height / 3, by = b.y + b.height / 3;
    return new Point[] {new Point(a.x + a.width * 3 / 4, ay), new Point(x, ay), new Point(x, by), new Point(b.x + b.width * 3 / 4, by)};
  }

  /** A line going back up (a loop): out of the source's left side, up past everything, into the target's left side. */
  private static Point[] backEdge(Rectangle a, Rectangle b, java.util.Collection<Rectangle> all, int idx) {
    int top = Math.min(a.y, b.y), bottom = Math.max(a.y + a.height, b.y + b.height);
    int left = Math.min(a.x, b.x);
    for (Rectangle r : all) {
      if (r.y + r.height < top || r.y > bottom || r.contains(a) || r.contains(b)) continue; // only what is between them
      if (r.x < Math.max(a.x, b.x) && r.x + r.width > left - 30) left = Math.min(left, r.x);
    }
    int x = left - 30 - idx * 15;
    int ay = a.y + a.height / 2, by = b.y + b.height / 2;
    return new Point[] {new Point(a.x, ay), new Point(x, ay), new Point(x, by), new Point(b.x, by)};
  }

  private Object fieldsPackage;

  /** One package that holds the field lists of objects that have no class. */
  private Object fieldsPackage() {
    if (fieldsPackage == null) {
      fieldsPackage = find("MODEL_TYPE_PACKAGE", "Object fields");
      if (fieldsPackage == null) {
        fieldsPackage = R.tryCall(factory, "createPackage");
        if (fieldsPackage != null) {
          R.tryCall(fieldsPackage, "setName", "Object fields");
          existing.put(R.fieldOr(F, "MODEL_TYPE_PACKAGE", "Package") + "\u0000object fields", fieldsPackage);
        }
      }
    }
    return fieldsPackage;
  }

  /** The shape of the swimlane a node belongs to. */
  private static Object laneShape(Uml.Diagram d, Uml.Node n, Map<Uml.Node, Object> shapes) {
    Uml.Node lane = d.node("lane\u0000" + n.lane);
    return lane == null ? null : shapes.get(lane);
  }

  // ------------------------------------------------------------------ helpers

  String freeDiagramName(String title) {
    if (!diagramNamesLoaded) {
      diagramNamesLoaded = true;
      Object arr = R.tryCall(project, "toDiagramArray");
      if (arr != null) {
        for (int i = 0; i < java.lang.reflect.Array.getLength(arr); i++) {
          Object name = R.tryCall(java.lang.reflect.Array.get(arr, i), "getName");
          if (name != null) usedDiagramNames.add(name.toString().toLowerCase(Locale.ROOT));
        }
      }
    }
    String name = title;
    for (int i = 2; usedDiagramNames.contains(name.toLowerCase(Locale.ROOT)); i++) name = title + " (" + i + ")";
    usedDiagramNames.add(name.toLowerCase(Locale.ROOT));
    return name;
  }

  private static Rectangle bounds(Object shape, Rectangle fallback) {
    int x = num(R.tryCall(shape, "getX"), fallback.x), y = num(R.tryCall(shape, "getY"), fallback.y);
    int w = num(R.tryCall(shape, "getWidth"), fallback.width), h = num(R.tryCall(shape, "getHeight"), fallback.height);
    return new Rectangle(x, y, w > 0 ? w : fallback.width, h > 0 ? h : fallback.height);
  }

  private static int num(Object o, int fallback) { return o instanceof Number ? ((Number) o).intValue() : fallback; }

  private static String label(Uml.Node n) { return n.name == null || n.name.length() == 0 ? "the " + n.kind : n.name; }

  private void warn(String s) { if (!warnings.contains(s)) warnings.add(s); }

  private void log(String s) { log.append(s).append('\n'); }
}
