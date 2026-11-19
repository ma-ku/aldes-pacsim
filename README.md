# pacsim — an Aldes installation on the clone's serial port

`pacsim` is a small Swing application that plays the part of an **Aldes T.One
AquaAIR** on the USB serial port of the ESP32-S31: every 30 seconds it reports
an installation state (the 90-byte `0x21` report, or the 112-byte `0x22` after
the gateway's init frame), answers a `0x42` poll with the 119-byte `0x23` time
programming and accepts `0x10` commands (`FD FA 58 FF 10 …`) that the clone
sends to the "installation".

That way the clone can be checked without a real heat pump: control commands
from Home Assistant reach pacsim through the clone, pacsim answers with the new
state, and the protocol pane shows every frame as a line of text.

## Build and run

Requirements: JDK 21+ (tested with GraalVM 25), Maven and jSerialComm 2.11 in
the local Maven cache. The GUI is plain Swing from the JDK — no extra UI
dependency.

```sh
mvn -o clean package        # tests + target/pacsim.jar + target/lib
./run.sh                    # window, manual mode
./run.sh --auto             # first serial port, the tick sends by itself
./run.sh --port cu.usbmodem1101
```

`--port NAME` connects that port at startup; the name is the one macOS or Linux
reports (`ls /dev/cu.*` helps).

## What the application does

* **Report every 5 s** — in automatic mode (`--auto` or `--port`) pacsim sends
  the report to the clone and the PAC clock advances with it (forward only).
  Without automatic mode the countdown only shows the tick; sending is done
  with **Report now**.
* **Editable values** — setpoints K1–K9 (16–30 °C, 0.5 °C steps) and measured
  temperatures (−40…60 °C, 0.1 °C steps) are spinners: the arrows step, typed
  text is taken over with Enter or when the field loses the focus. The
  **Thermostats** selector below the zone table sets how many the installation
  has (3–9, default 6): only K1–Kn are shown, while the `0x21` report keeps its
  nine zone slots and the hidden values stay stored. An empty field means
  "no value" (K2–K9 start without a setpoint, i.e. `sans consigne`); the `×`
  next to a setpoint clears it. The 30 s tick never overwrites a field while
  it is being edited. Air and ECS mode, the ECS level (byte 73, 0–100 %),
  vacation departure and return as well as the PAC clock are edited as before.
* **Keep-alive dialogue (optional, off by default)** — the **Keep-alive**
  checkbox in the window switches it on; only then, and while a port is open
  (in both modes, because the session is independent of the report interval),
  pacsim pings with `FA FD 07 FF 4A FE BB` every 2 to 20 s — the cadence is
  drawn per ping like the reference installation. Switching on starts a fresh
  session, so the first ping goes out at once; switching off ends it and stops
  the pings. The clone's pong `FD FA 07 FF 13 FE F2` is logged and counted, the
  init frame `02 03 04 20 00 01 02 42 A2` ends debug mode: from then on pacsim
  sends the 90-byte `0x21` report instead of the `0x25` debug page. A Modbus
  read request (`02 03 04 20 00 01 84 C3`) is logged but not answered.
* **Three datagrams, switchable at any time** — the **Datagram** selector in the
  window picks what pacsim puts on the wire: **debug `0x25`** (175 bytes, the
  verbose page captured in protocol.md §3, sent unchanged with a recomputed
  checksum), **report `0x21`** (90 bytes, the frame the clone decodes — the state
  at start and after the init frame) and **standard `0x22`** (112 bytes, the
  monitoring page with the sensor map). Every tick and every answer to a client
  update uses the selected datagram, and a new connection starts again with the
  `0x21` report.
* **Hors-gel preset** — the *away* preset `hors-gel` sets the departure to the
  current date and the return to `0` (not set), i.e. frost protection without
  an end; the status line then shows `hors-gel (no return)`.
* **Flashing on board updates** — if the clone sends back a report with
  different values (for example after a write command), the affected controls
  are highlighted in yellow for a moment and the new value is taken over. If
  our own report comes back unchanged (echo), nothing flashes. An empty value
  (`00 00` = "sans consigne" or `0xFFFF` = not set) counts as the same value as
  "empty".
* **Update requests from the client** — every 88-byte `0x10` frame the clone
  sends is applied to the model: every setpoint K1–K9 the frame carries, air
  mode (offset 81), ECS mode (82) and "away cleared" (bytes 5–12 = `00`) are
  taken over together, so a full parameter list with several changes works as
  well as the clone's single-change frames. Fields masked with `FF FF` stay
  untouched, and one unusable field rejects the whole frame. The changed
  controls flash and the log names the effect (`command applied: Setpoint K2 =
  22.0 °C, Air = 1 Chauffage Confort`). About 200 ms later pacsim answers with
  an updated report — as the installation answers a write with a `0x21`
  (protocol.md §7); a burst of requests ends in one report, and without an open
  port the answer is only logged. Vacation dates in bytes 5–12 are not
  simulated; they and unknown commands are logged as `command ignored: …`
  instead of being dropped.
* **Time programming on request** — the clone polls the programs with
  `FD FA 08 FF 42 22 FE A0`; pacsim answers with the 119-byte `0x23` page built
  from the Programs tab (four programs A–D, seven days from Monday, four LE
  bytes per day). Nothing else triggers a `0x23`: it is never sent on its own
  and the datagram selector does not offer it, so it also goes out in debug and
  standard mode. The log names the comfort hours per program.
* **Programs tab** — the second tab shows the four time programs A–D, each as
  its own grid: seven days (rows, Monday first) over 24 hours (columns). A
  click switches an hour between comfort (orange) and reduced (grey), dragging
  paints over every cell it crosses. The defaults follow protocol.md — A/C
  7–9 and 17–22 on weekdays, B/D additionally 12–14, all four 8–23 at the
  weekend — and a tooltip names the hour under the pointer.
* **Values survive a restart** — every change (setpoint, temperature, air/ECS,
  the ECS level, the number of thermostats, away dates, keep-alive, the hours of
  programs A–D)
  is written to `~/.pacsim.properties` and loaded at start; `-Dpacsim.config=<path>` selects
  another file. Missing entries keep the defaults, unreadable ones are skipped,
  so a hand-edited file cannot stop the simulation. The PAC clock is not
  stored — a simulator starts with the current time.
* **Text protocol** — every frame sent and received appears line by line with
  time, direction (`TX`/`RX`), opcode, length, checksum check and hex dump; the
  clone's commands are decoded into text (`TX opcode=0x10 … | setpoint K2 =
  22.00 °C | air = 5 | away cleared`).

## Protocol in brief

| Frame | Layout |
| --- | --- |
| Report (installation → clone) | 90 bytes, fixed header `FF FD 5A FF 21`, `0xFE` at index 88, checksum at 89 |
| Standard report (after the init frame) | 112 bytes, header `FF FD 70 FF 22`, sensor page from 5, 26-byte trailer from 86 with the Unix timestamp at 100–103, `0xFE` at 110, checksum at 111 |
| Command (clone → installation) | 88 bytes, header `FD FA 58 FF 10`, `0xFE` at 86, checksum at 87 |
| Time programming (only on the `0x42` request) | 119 bytes, header `FF FD 77 FF 23`, 4 programs × 7 days × 4 LE bytes from 5 (fills 5–116), `0xFE` at 117, checksum at 118 |
| Program request (clone → installation) | 8 bytes: `FD FA 08 FF 42 22 FE A0` |
| Ping / pong | 7 bytes each: `FA FD 07 FF 4A FE BB` / `FD FA 07 FF 13 FE F2` |
| Init / Modbus read | `02 03 04 20 00 01 02 42 A2` / `02 03 04 20 00 01 84 C3` (no `FA FD`, no zero sum) |

Field positions in the report (as in `report21.py`; K5–K9 use the bytes the
reference leaves unused):

| Bytes | Content |
| --- | --- |
| 5–8 / 9–12 | vacation departure / return, DOS/FAT time LE32, `0` = not set |
| 13–30 | setpoints K1–K9, LE16 in 1/100 °C, `0xFFFF` = not set |
| 53–70 | temperatures K1–K9, LE16 in 1/100 °C, two's complement, `0xFFFF` = empty |
| 73 | ECS level, one byte in percent (0–100); 71–72 and 74–76 stay `00` |
| 77–80 | PAC clock, DOS/FAT LE32 |
| 81 / 82 | air / ECS mode |

Field positions in the `0x22` standard report (sensor offsets from the 0x24
notes in `protocol.md`, an assumption for 0x22):

| Bytes | Content |
| --- | --- |
| 5–85 | sensor page, offsets relative to byte 5: 29 valve angle, 45 ThoA, 47/49 DHW top/bottom, 57 Th3, 61 ThoR1, 63 DHW pressure, 65 absent (−40 °C), 75 compressor outlet; unassigned = `0xFFFF` |
| 86–99 | trailer status bytes (`00`) |
| 100–103 | Unix timestamp LE32 (host clock) |
| 104–109 | trailer status bytes (`00`) |

Field positions in the `0x23` time programming (`protocol.md` §5, program-major
order from the "Programs A, B, C, D × 7 days" wording):

| Bytes | Content |
| --- | --- |
| 5–32 | program A, Monday…Sunday, 4 LE bytes per day (bit 0 = 0 h, 1 = comfort) |
| 33–60 | program B |
| 61–88 | program C |
| 89–116 | program D |

Framer rule: only at `FA FD` the length sits in byte 2. `FD FA` and the
installation header `FF FD 5A …` carry **no** length there; such frames count
as complete as soon as they close cleanly (`0xFF` at byte 3, `0xFE` in front of
the checksum, checksum 0) — a `0xFE` inside the payload does not end the frame.
That matches `frame_feed()` in the clone and the captures. Frames starting with
`0x02` (Modbus/init) have neither a length nor a zero sum; they are matched
against the two documented frames.

## Code layout

| Class | Purpose |
| --- | --- |
| `Main` | arguments, window, 5 s tick |
| `PacSimApp` | Swing GUI, display, flashing, protocol log, tabs |
| `SimulationModel` | state of the "installation", comparison with the board, commands |
| `Programs` | time programs A–D: 7 days × 24 hours, one comfort bit per hour |
| `ProgramGrid` | the clickable grid of one program (click toggles, drag paints) |
| `ValueSpinner` | spinner for setpoints/temperatures with an empty "no value" state |
| `Settings` | `~/.pacsim.properties`: values of the window, loaded at start |
| `KeepAlive` | ping cadence 2–20 s, pong/init/Modbus classification, datagram mode |
| `AldesProtocol` | build/read the reports, read commands, checksums, clock, session frames |
| `Framer` | framing as in the clone |
| `WriteFrames` | 88-byte commands (template from `pico.py`) |
| `SerialLink` | jSerialComm, receive thread |

## Tests

```sh
mvn -o clean test
```

The tests check the byte layout against `pico.py`/`report21.py`, the framer
rules (including the `FD FA` report byte by byte and the `0x02` frames), the
`0x22` standard report, the report roundtrip, the keep-alive dialogue, the
`0x23` program report with its `0x42` request, the values in
`~/.pacsim.properties`, the program grid (pixels → hour/day, click, drag
painting) and the path report → framer → model → flashing fields. No hardware
is needed.

