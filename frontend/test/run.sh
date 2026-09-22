#!/bin/bash
# Tests of the client (the Java front). They run the real client without a
# window and without the server (see Rig.java), so they need neither a
# screen nor a server running. From the root of the repository:
#
#   frontend/test/run.sh                    every test
#   frontend/test/run.sh FlowTest           only the ones named
#
# Nothing is written in the project: the classes go to a temporary folder.
# Exits with 1 if a check fails, 2 if the tests can't be built.

HERE=$(cd "$(dirname "$0")" && pwd)
SRC="$HERE/../src/main/java"
PORT=18082 # where the tests wait for the client to connect
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

# The client connects to 127.0.0.1:8080, where a real server may be
# running: the tests use a copy of MainController that connects to PORT.
mkdir -p "$WORK/src/com/lso"
sed "s/new Socket(\"127.0.0.1\", 8080)/new Socket(\"127.0.0.1\", $PORT)/" \
    "$SRC/com/lso/MainController.java" > "$WORK/src/com/lso/MainController.java"
if ! grep -q "new Socket(\"127.0.0.1\", $PORT)" "$WORK/src/com/lso/MainController.java"
then
    echo "run.sh: MainController.java no longer has new Socket(\"127.0.0.1\", 8080): update the sed in run.sh"
    exit 2
fi

# The client, apart from MainController (the copy above) and MainFrame (the
# stub in stub/, which opens no window), and the tests.
mapfile -t SOURCES < <(find "$SRC" -name '*.java' ! -name MainController.java ! -name MainFrame.java)
if ! javac -nowarn -d "$WORK/classes" "${SOURCES[@]}" "$WORK/src/com/lso/MainController.java" \
           "$HERE/stub/com/lso/view/MainFrame.java" "$HERE"/*.java 2> "$WORK/javac.log"
then
    cat "$WORK/javac.log"
    exit 2
fi

TESTS=("$@")
if [ ${#TESTS[@]} -eq 0 ]
then
    for f in "$HERE"/*Test.java
    do
        TESTS+=("$(basename "$f" .java)")
    done
fi

FAILED=0
for t in "${TESTS[@]}"
do
    # A test takes a few seconds: one that hangs is stopped.
    timeout 120 java -Djava.awt.headless=true -Dtest.port=$PORT -cp "$WORK/classes" "$t"
    case $? in
        0)   ;;
        124) echo "$t: stopped after 120 s"; FAILED=1 ;;
        *)   FAILED=1 ;;
    esac
done
exit $FAILED
