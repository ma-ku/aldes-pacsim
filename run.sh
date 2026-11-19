#!/bin/sh
# Starts pacsim (plain Swing, no extra UI modules).
#
#   ./run.sh                      window, manual mode (the tick does not send)
#   ./run.sh --auto               window, first serial port, 30 s tick
#   ./run.sh --port cu.usbmodem1101
#
# Requirement: JDK 21+ (tested with 25) and a Maven build in target/:
#   mvn -o clean package
set -eu

cd "$(dirname "$0")"

if [ ! -f target/pacsim.jar ]; then
    echo "target/pacsim.jar missing -- first run: mvn -o clean package" >&2
    exit 1
fi

# Runtime JARs (jSerialComm) live in target/lib.
LIB="target/lib"
if [ ! -d "$LIB" ]; then
    echo "$LIB missing -- first run: mvn -o clean package" >&2
    exit 1
fi

exec java -cp "target/pacsim.jar:$LIB/*" de.aldes.pacsim.Main "$@"
