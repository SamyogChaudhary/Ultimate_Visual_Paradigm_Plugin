# PlantUML ERD Importer — Visual Paradigm plugin

Turns crow's foot ER diagrams written in PlantUML into real Visual Paradigm ER diagrams: tables, columns (PK, FK, data type, NOT NULL, unique), crow's foot relationships, and the diagram itself. Works in Visual Paradigm **Community Edition**.

## Install
1. In Visual Paradigm: **Help > Install Plugin**.
2. Choose **Install from a zip of plugin** and pick [`release/PlantUML_ERD_Importer_plugin.zip`](release/PlantUML_ERD_Importer_plugin.zip)
   (or **Install from a folder of plugin** with the `plantuml.erd.importer` folder).
3. Restart Visual Paradigm.

## Use
In the **Plugins** tab:
- **Import ERD File** — pick one or more `.puml` files.
- **Paste ERD Code** — paste everything from `@startuml` to `@enduml`.

Each `@startuml … @enduml` block becomes one ER diagram named after its title. See [`plantuml.erd.importer/HOW_TO_INSTALL.txt`](plantuml.erd.importer/HOW_TO_INSTALL.txt) for the supported PlantUML syntax, re-import behaviour and troubleshooting, and [`example.puml`](example.puml) for a sample diagram.

## Contents
- `plantuml.erd.importer/` — the plugin folder (`plugin.xml`, compiled classes, icons)
- `src/erdimport/` — Java source
- `release/` — the installable zip
- `build.sh` — rebuilds the classes and the zip

## Build from source
Needs a JDK (`javac`) and Visual Paradigm's `lib/openapi.jar`:

```bash
VP_HOME=~/Visual_Paradigm_CE_18.1 ./build.sh
```

| File | Role |
|---|---|
| `ImporterPlugin.java` | plugin entry point |
| `ImportFileAction.java`, `PasteAction.java` | the two toolbar buttons |
| `Runner.java` | asks for the PlantUML, runs the import, reports the result |
| `PumlParser.java` → `Puml.java` | parses PlantUML into plain Java objects |
| `VpImporter.java` | creates tables, columns, relationships and the diagram in VP |
| `R.java` | reflection helpers for calling the VP API across versions |
