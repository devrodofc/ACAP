#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JOCL_JAR="${JOCL_JAR:-}"

if [[ -z "$JOCL_JAR" ]]; then
  if [[ -f "$ROOT_DIR/lib/jocl-2.0.4.jar" ]]; then
    JOCL_JAR="$ROOT_DIR/lib/jocl-2.0.4.jar"
  elif [[ -f "$HOME/Downloads/jocl-2.0.4.jar" ]]; then
    JOCL_JAR="$HOME/Downloads/jocl-2.0.4.jar"
  else
    echo "Erro: jocl-2.0.4.jar nao encontrado." >&2
    echo "Coloque em lib/jocl-2.0.4.jar, em ~/Downloads/jocl-2.0.4.jar, ou defina JOCL_JAR=/caminho/jocl-2.0.4.jar." >&2
    exit 1
  fi
fi

mkdir -p "$ROOT_DIR/out"
mkdir -p "$ROOT_DIR/native"

JAVA_LIBRARY_PATH="$ROOT_DIR/native"
if [[ ! -e "$ROOT_DIR/native/libOpenCL.so" ]]; then
  OPENCL_SO="$(ldconfig -p 2>/dev/null | awk '/libOpenCL[.]so[.]1/ && /x86-64/ {print $NF; exit}')"
  if [[ -n "${OPENCL_SO:-}" && -f "$OPENCL_SO" ]]; then
    ln -s "$OPENCL_SO" "$ROOT_DIR/native/libOpenCL.so"
  fi
fi

javac -encoding UTF-8 -cp "$JOCL_JAR" -d "$ROOT_DIR/out" "$ROOT_DIR/src/Main.java"
LD_LIBRARY_PATH="$JAVA_LIBRARY_PATH:${LD_LIBRARY_PATH:-}" \
  java -Djava.library.path="$JAVA_LIBRARY_PATH" -cp "$ROOT_DIR/out:$JOCL_JAR" Main "$@"
