#!/usr/bin/env bash
#
# Sweep every example program and record what it compiles to and what it does.
#
# Two gates use this. The **byte-identity** gate of an ordinary compiler change reads the `jar.md5`
# lines: an unrelated change must leave every jar bit-for-bit the same. The **behavioural** gate of a
# change whose output legitimately differs wholesale (effects v6's flag day, docs/effects.md §8) reads
# the `exit` and `stdout` lines instead — those must be identical — while `jar.size`,
# `jar.instructions` and `module.instructions` are the size difference the commit is asked to state.
#
# Usage:
#   scripts/example-sweep.sh [-o OUT] [-m MODULE]... [--no-run] [--keep-jars DIR]
#
#   -o OUT          write the report here (default: stdout)
#   -m MODULE       sweep only this example (repeatable; default: every module in examples/src)
#   --no-run        compile and measure only, do not run the jars
#   --keep-jars DIR copy each jar here (they are otherwise left in target/ and overwritten)
#
# The report is line-oriented and deterministic; every non-reproducible header line starts with `#`,
# so two reports are compared with
#
#   diff <(grep -v '^#' before.txt) <(grep -v '^#' after.txt)
#
# Traps this script exists to encode (reference_verification_harness_recipes):
#   - the compiler's main class is `…eliotc.compiler.Main`, not `…eliotc.Main`;
#   - `build.mill` appends the layer paths, so a direct invocation must pass all three `--path`s;
#   - the cache (`target/.eliot-*`) must go between compiles or stale facts replay;
#   - examples reading stdin behave differently on a tty, so stdin is always /dev/null;
#   - a pre-change baseline must be swept from a PRISTINE `out/` (stash the change, `./mill compile`,
#     sweep, unstash) — this script does not do that for you.
#
set -u -o pipefail

readonly INVOKED_FROM="$PWD"
cd "$(dirname "$0")/.."
readonly REPO="$PWD"

# Paths given on the command line are the caller's, not the repo root's.
resolve() { case "$1" in /*) echo "$1" ;; *) echo "$INVOKED_FROM/$1" ;; esac; }

out=/dev/stdout
run=yes
keep_jars=
modules=()

while [ $# -gt 0 ]; do
  case "$1" in
    -o) out="$(resolve "$2")"; shift 2 ;;
    -m) modules+=("$2"); shift 2 ;;
    --no-run) run=no; shift ;;
    --keep-jars) keep_jars="$(resolve "$2")"; shift 2 ;;
    -h|--help) sed -n '2,30p' "$0"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

if [ ${#modules[@]} -eq 0 ]; then
  while IFS= read -r f; do modules+=("$(basename "$f" .els)"); done < <(ls "$REPO"/examples/src/*.els | sort)
fi

classpath="$(./mill show examples.runClasspath 2>/dev/null |
  sed -n 's/^ *"[a-z]*ref:v[0-9]*:[0-9a-f]*://p' | sed 's/",\?$//' | paste -sd:)"
[ -n "$classpath" ] || { echo "could not resolve examples.runClasspath" >&2; exit 1; }

[ -n "$keep_jars" ] && mkdir -p "$keep_jars"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

{
  echo "# eliot example sweep — scripts/example-sweep.sh"
  echo "#"
  echo "# One block per module in examples/src. A module with no \`main\` compiles to no jar and its block"
  echo "# is just the \`compile:\` line; the true baseline is 45 of 50."
  echo "#"
  echo "#   jar.md5             the byte-identity oracle for a change that must not alter output"
  echo "#   jar.size            bytes of the reproducible jar"
  echo "#   jar.classes         classes in the jar"
  echo "#   jar.instructions    bytecode instructions over all of them — what a specialisation change moves"
  echo "#   main.class          the jar's Main-Class: the synthesized entry stub, not the program"
  echo "#   main.instructions   its instruction count (the same handful everywhere)"
  echo "#   module.instructions the class named after the module — the program's own code"
  echo "#   exit, stdout        one run, stdin /dev/null (a tty changes what the stdin-reading examples"
  echo "#                       print), 60s timeout"
  echo "#"
  echo "# Compare two reports with:  diff <(grep -v '^#' before.txt) <(grep -v '^#' after.txt)"
  echo "#"
  echo "# commit: $(git rev-parse HEAD)  sources: $(git diff --quiet HEAD && echo clean || echo dirty)"
  echo "# modules: ${#modules[@]}"
  echo
} > "$out"

compiled=0
for module in "${modules[@]}"; do
  jar="$REPO/target/$module.jar"
  rm -rf "$REPO"/target/.eliot-* "$jar"

  java -cp "$classpath" com.vanillasource.eliot.eliotc.compiler.Main \
    jvm exe-jar examples/src/ -m "$module" \
    --path lang/eliot/src --path stdlib/eliot/src --path jvm/eliot/src > "$tmp/compile.out" 2>&1
  compile_status=$?

  {
    echo "== $module"
    if [ ! -f "$jar" ]; then
      # A failed build leaves no jar (project_build_artifact_hygiene), so the jar's absence is the verdict.
      echo "compile: no-jar (exit $compile_status)"
      echo
      continue
    fi
    compiled=$((compiled + 1))
    echo "compile: ok"
    echo "jar.md5: $(md5sum < "$jar" | cut -d' ' -f1)"
    echo "jar.size: $(stat -c%s "$jar")"

    # Code size, at three granularities. The jar's own Main-Class is the synthesized entry stub and is
    # the same handful of instructions everywhere; the class named after the module is the program, and
    # the jar total is what a specialisation change actually moves.
    unzip -Z1 "$jar" '*.class' | sed 's/\.class$//; s|/|.|g' | sort > "$tmp/classes"
    echo "jar.classes: $(wc -l < "$tmp/classes")"
    xargs -a "$tmp/classes" javap -p -c -cp "$jar" > "$tmp/javap" 2>/dev/null
    echo "jar.instructions: $(grep -cE '^ +[0-9]+: ' "$tmp/javap")"

    main_class="$(unzip -p "$jar" META-INF/MANIFEST.MF | tr -d '\r' | sed -n 's/^Main-Class: *//p')"
    echo "main.class: ${main_class:-none}"
    [ -n "$main_class" ] &&
      echo "main.instructions: $(javap -p -c -cp "$jar" "$main_class" | grep -cE '^ +[0-9]+: ')"
    if grep -qx "$module" "$tmp/classes"; then
      echo "module.instructions: $(javap -p -c -cp "$jar" "$module" | grep -cE '^ +[0-9]+: ')"
    fi

    if [ "$run" = yes ]; then
      timeout 60 java -jar "$jar" < /dev/null > "$tmp/run.out" 2> "$tmp/run.err"
      echo "exit: $?"
      echo "stdout:"
      sed 's/^/  | /' "$tmp/run.out"
    fi
    echo
  } >> "$out"

  [ -n "$keep_jars" ] && [ -f "$jar" ] && cp "$jar" "$keep_jars/"
done

echo "# compiled: $compiled/${#modules[@]}" >> "$out"
