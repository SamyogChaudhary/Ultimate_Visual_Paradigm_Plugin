#!/usr/bin/env bash
# Compile the plugin and package the installable zip.
# Needs a JDK (javac) and Visual Paradigm's openapi.jar.
# Usage: VP_HOME=~/Visual_Paradigm_CE_18.1 ./build.sh
set -euo pipefail
cd "$(dirname "$0")"
VP_HOME="${VP_HOME:-$HOME/Visual_Paradigm_CE_18.1}"
API="$VP_HOME/lib/openapi.jar"
[ -f "$API" ] || { echo "openapi.jar not found at $API (set VP_HOME)"; exit 1; }
OUT=plantuml.importer
rm -rf "$OUT/classes/erdimport"
# Java 8 bytecode so it loads in older Visual Paradigm versions too.
# --release needs the JDK's ct.sym; runtimes without it fall back to -source/-target.
if ! javac --release 8 -nowarn -encoding UTF-8 -cp "$API" -d "$OUT/classes" src/erdimport/*.java 2>/dev/null; then
  javac -source 8 -target 8 -Xlint:-options -nowarn -encoding UTF-8 -cp "$API" -d "$OUT/classes" src/erdimport/*.java
fi
mkdir -p "$OUT/classes/icons" && cp "$OUT"/icons/*.png "$OUT/classes/icons/"
mkdir -p release && rm -f release/*.zip
zip -qr release/PlantUML_Importer_plugin.zip "$OUT"
echo "Built release/PlantUML_Importer_plugin.zip"
