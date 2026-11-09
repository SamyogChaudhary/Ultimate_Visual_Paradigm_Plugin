# PlantUML Importer — Visual Paradigm plugin

Turns PlantUML diagrams into real Visual Paradigm models and diagrams — editable model elements, not pictures. Works in Visual Paradigm **Community Edition**.

| PlantUML | Becomes in Visual Paradigm |
|---|---|
| ER diagram (crow's foot) | tables, columns (PK, FK, type, NOT NULL, unique), relationships |
| Class diagram | classes, interfaces, enums, abstract classes, attributes, operations, packages, inheritance, realization, association, aggregation, composition, dependency, notes |
| Use case diagram | actors, use cases, system boundary, associations, include, extend, generalization, notes |
| Sequence diagram | lifelines (actor, boundary, control, entity, database …), sync/async/return/self/create/destroy messages, activation bars, autonumber, alt/opt/loop/par/break/critical/group fragments, ref, notes |
| Activity diagram | actions, start/stop/end, if/elseif/else, switch, while, repeat, fork/join, split, swimlanes, labels, notes, old `(*) -->` syntax |
| State machine diagram | states, composite states, initial/final, choice, fork, join, history, transitions with labels, entry/exit/do, notes |
| Component diagram | components, provided and required interfaces, packages, nodes, databases, dependencies |
| Deployment diagram | nodes, devices, artifacts, components, databases, clouds …, nesting, communication paths |
| Object diagram | objects with classes and fields, maps, links |

The diagram type of each `@startuml … @enduml` block is detected automatically, so one file can mix types. Every diagram gets a tidy starting layout; see [`HOW_TO_INSTALL.txt`](plantuml.importer/HOW_TO_INSTALL.txt) for the full syntax and the few things that are not imported.

## Install
1. In Visual Paradigm: **Help > Install Plugin**.
2. Choose **Install from a zip of plugin** and pick [`release/PlantUML_Importer_plugin.zip`](release/PlantUML_Importer_plugin.zip)
   (or **Install from a folder of plugin** with the `plantuml.importer` folder).
3. Restart Visual Paradigm.

If the older **PlantUML ERD Importer** is installed, remove it first; this plugin does everything it did.

## Use
In the **Plugins** tab:
- **Import PlantUML File** — pick one or more `.puml` files.
- **Paste PlantUML Code** — paste everything from `@startuml` to `@enduml`.

Each block becomes one diagram named after its title. See [`plantuml.importer/HOW_TO_INSTALL.txt`](plantuml.importer/HOW_TO_INSTALL.txt) for the supported syntax, re-import behaviour and troubleshooting, and [`examples/`](examples) for sample diagrams.

## Build from source
Needs a JDK (`javac`) and Visual Paradigm's `lib/openapi.jar`:

```bash
VP_HOME=~/Visual_Paradigm_CE_18.1 ./build.sh
```

## Test without the GUI
Visual Paradigm's command-line runner can load the plugin and run an import headless, and its exporter can render the result:

```bash
cp -r plantuml.importer ~/.config/VisualParadigm/plugins/      # Linux plugin folder
cd ~/Visual_Paradigm_CE_18.1/scripts
sh Plugin.sh -project base.vpp -pluginid plantuml.importer -pluginargs "/abs/path/out.vpp /abs/path/examples/class.puml"
sh ExportDiagramImage.sh -project out.vpp -out img -diagram '*' -type png
```

| File | Role |
|---|---|
| `ImporterPlugin.java` | plugin entry point and command-line support |
| `ImportFileAction.java`, `PasteAction.java` | the two toolbar buttons |
| `Runner.java` | reads the PlantUML, sends each block to the right importer, reports the result |
| `UmlParser.java` → `Uml.java` | splits blocks, detects the diagram type, parses class, use case, component, deployment and object diagrams |
| `FlowParser.java` | parses activity and state machine diagrams |
| `SeqParser.java`, `SeqImporter.java` | parses sequence diagrams and builds them in VP |
| `UmlImporter.java`, `Layout.java` | creates the model elements, relationships, diagram and layout in VP |
| `PumlParser.java` → `Puml.java` | parses ER diagrams |
| `VpImporter.java` | creates tables, columns, relationships and the ER diagram in VP |
| `R.java` | reflection helpers for calling the VP API across versions |
