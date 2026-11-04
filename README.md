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
- `release/` — the installable zip

> Note: this repo currently holds the compiled plugin only; the Java source is not included yet.
