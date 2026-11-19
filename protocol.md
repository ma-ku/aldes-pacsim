 
# Protocol Findings

## 1. Link Characteristics

- **Speed:** 115200 baud, 8 bits, no parity, 1 stop bit.
- **Interface:** USB-Serial
- **Roles:** The heat pump queries the gateway with **Pings**. The gateway maintains the session with **Pongs** and **Modbus** requests.

* * *

## 2. Universal Frame Structure

All frames follow this basic format:

[HEADER] [LENGTH] [FF] [TYPE/ID] [DATA...] [CHECKSUM]

### The 3 header types

- **`FA FD`**: Message outgoing from the heat pump to the gateway (Monitoring/Info).
- **`FD FA`**: Message incoming from the gateway to the heat pump (Commands/Requests).
- **`FF FD`**: Direct response from the heat pump to a gateway command (Confirmation).

* * *

## 3. The Keep-Alive Dialogue

This is the basic cycle for the heat pump to accept communicating "normally".

1. **The Ping (heat pump):** `FA FD 07 FF 4A FE BB` (Every 2 to 20 sec).
2. **The Pong (gateway):** `FD FA 07 FF 13 FE F2`.
3. **The Initialization (gateway):** `02 03 04 20 00 01 02 42 A2`.

- _This specific frame forces the heat pump to exit Debug mode (175 bytes) and switch to Standard mode (112 bytes)._

The debug frame looks like the following datagram:

FA:FD:AF:FF:25:09:00:00:00:00:00:00:00:2B:00:00:00:C5:07:00:00:00:00:00:00:1F:25:00:00:09:00:00:00:00:00:00:00:2A:00:00:00:C5:07:00:00:00:00:00:00:42:24:00:00:20:00:00:00:00:00:00:00:66:00:00:00:C0:19:00:00:00:00:00:00:14:52:00:00:1D:00:00:00:00:00:00:00:27:00:00:00:56:17:00:00:00:00:00:00:62:1F:00:00:1C:00:00:00:00:00:00:00:01:00:00:00:88:16:00:00:00:00:00:00:CE:00:00:00:12:00:00:00:00:00:00:00:02:00:00:00:7C:0E:00:00:00:00:00:00:9C:01:00:00:17:00:00:00:00:00:00:00:00:00:00:00:82:12:00:00:00:00:00:00:00:00:00:00:FE:29

* * *

## 4. Report Catalog (Types & IDs)

| **ID (Hex)** | **Length** | **Name** | **Main Content** |
| --- | --- | --- | --- |
| **`0x21`** | 64 b. | **Configuration** | Target setpoints (D0 07 = 20.0°C), operating modes. |
| **`0x22`** | 112 b. | **Monitoring** | Actual sensor temperatures, fan speeds. |
| **`0x23`** | 119 b. | **Scheduling** | 7d/24h time grid (Bitmaps) for programs A,B,C,D. |
| **`0x25`** | 175 b. | **Full Debug** | Verbose report sent by default (contains a lot of 0x00). |

* * *

## 5. Value Decoding

### Temperatures (16-bit Little Endian Format)

The values are in degrees Celsius x 100.

- **Example:** `D0 07` ➡ `07 D0` (Hex) ➡ `2000` (Dec) ➡ **20.00°C**.
- **Example:** `6C 07` ➡ `07 6C` (Hex) ➡ `1900` (Dec) ➡ **19.00°C**.

### Time Programming (Type 0x23)

Same format as the remote control's Modbus (registers 31200 to 31255).

- Each day is encoded on 4 bytes (32 bits).
- **LSB (00h-15h)**: 16 bits for the first 16 hours.
- **MSB (16h-23h)**: 8 bits for the last 8 hours.
- _A bit at `1` = Comfort mode, a bit at `0` = Eco mode._

* * *

## 6. Forcing Commands (Polling)

The gateway can "choose" what it wants to read by sending these frames:

- **Read the Setpoints (Type 21):**
  - `FD FA 08 FF 41 21 FE A2` (Proprietary request)
  - **OR** `02 03 04 20 00 01 84 C3` (Standard Modbus request)

- **Read the Time Programming (Prog A, B, C D) (Type 23):**
  - `FD FA 08 FF 42 22 FE A0` (The ID `42` requests the report following `22`).

* * *

## 7. Modification Logic (Writing)

To change a temperature, the gateway sends a **`0x58`** type frame:

1. It contains the full list of parameters.
2. Unchanged values are filled with **`FF FF`** (masking).
3. The target value is injected (e.g. `D0 07`).
4. The heat pump responds with a **`FF FD`** of type **`0x21`** to confirm.

* * *

## 8. End of Frame and Synchronization

Each large report ends with a final block of **26 bytes** including:

- **Bytes 14 to 17:** Unix Timestamp (seconds counter). E.g. `F9 A2 30 5C`.
- **Status Bytes:** Relay states, active errors.
- **Final checksum:** Starts with `FE`, followed by a byte computed from the frame sum.


# Messages

**0x23: time programming (119 bytes)**

- Programs A, B (heating), C, D (cooling) × 7 days, starting on Monday.
- 4 LE bytes per day, starting at offset 5: 1 bit per hour (bit 0 = 0 h), 1 = Comfort.
- At your place:
  - A and C: 7h–9h and 17h–22h on weekdays;
  - B and D: plus 12h–14h on weekdays;
  - all four: 8h–23h on weekends.

**0x24: sensors (87 bytes), signed LE16 /100**

- Validated against the HMI:
  - 45: outdoor ThoA;
  - 47 / 49: DHW top / bottom;
  - 57: gas line Th3;
  - 61: outdoor exchanger ThoR1;
  - 75: compressor outlet.

- Very likely: 63, DHW exchanger pressure (12.15 bar vs. 12.10 on the HMI).
- 29: 1/2" three-way valve angle (45°), as a raw value. 65: −40 °C, sensor absent.
- Not assigned:
  - Th4, Th5 and Th6, among offsets 51, 53, 55, 59, 69 and 71;
  - 79 and 81 (138.19 and 262.29).

- EEV1 is absent from this page.

**0x25 to 0x28: consumption (175 / 175 / 151 / 127 bytes), at startup only**

- 24-byte blocks starting at offset 5. Each block contains six LE32: `[DHW, cooling, heating]` in kWh, then the same values × rate in m€.
- **Current meters: page 0x26, block at offset 149**, identical to the HMI (81 / 605 / 0 kWh at a rate of €0.168).
- 0x27: archived totals. The 1st block (444 / 859 / 1017 kWh at €0.150) probably dates from before your reset. Three other sets of totals have no cost.
- 0x25, 0x26 and 0x28: 15 history blocks (per month, no doubt), with no date.


# Notes

Correction to my previous message: offset 81, which I was considering for DHW, acts on air mode in the tests done in my case. In the 88-byte write template `FD FA 58 FF 10 … FE sum`, offsets counted from zero, here are the values actually observed on the HMI:

| Offset 81 | State observed |
| --- | --- |
| `00` | Air OFF |
| `01` | Comfort Heating |
| `02` | ECO Heating |
| `05` | Comfort A/C |
| `06` | Boost A/C |

In the same template, offset **82** gave: `00` → DHW OFF, `01` → DHW ON, `02` → DHW Boost. The air mode field (81) is left at FF during these tests. The ON → Boost → ON round trip was confirmed, then OFF was also confirmed, with the A/C still in Comfort. The return to ON after this last OFF was delivered over USB; its last HMI confirmation is still awaited at the time of this update.

# Traces

Just traced exchange:

```
SOF #99
SETUP: 0x00/0
DATA0 (8): 80 06 00 01 00 00 08 00 
ACK
IN: 0x00/0
DATA1 (8): 12 01 00 02 02 02 00 40 
ACK
OUT: 0x00/0
DATA1: ZLP
ACK
SOF #100
SETUP: 0x00/0
DATA0 (8): 80 06 00 01 00 00 12 00 
ACK
IN: 0x00/0
DATA1 (18): 12 01 00 02 02 02 00 40 83 04 40 57 00 02 01 02 03 01 
ACK
OUT: 0x00/0
DATA1: ZLP
ACK
SETUP: 0x00/0
DATA0 (8): 00 05 01 00 00 00 00 00 
ACK
IN: 0x00/0
DATA1: ZLP
ACK
Folded 1 frame
SOF #102
SETUP: 0x01/0
DATA0 (8): 80 06 00 02 00 00 09 00 
ACK
IN: 0x01/0
DATA1 (9): 09 02 43 00 02 01 00 c0 32 
ACK
OUT: 0x01/0
DATA1: ZLP
ACK
SETUP: 0x01/0
DATA0 (8): 80 06 00 02 00 00 43 00 
ACK
IN: 0x01/0
DATA1 (64): 09 02 43 00 02 01 00 c0 32 09 04 00 00 01 02 02 01 00 05 24 00 10 01 05 24 01 00 01 04 24 02 02 05 24 06 00 01 07 05 82 03 08 00 10 09 04 01 00 02 0a 00 00 00 07 05 01 02 40 00 00 07 05 81 02 
ACK
IN: 0x01/0
NAK
IN: 0x01/0
DATA0 (3): 40 00 00 
ACK
OUT: 0x01/0
DATA1: ZLP
ACK
SETUP: 0x01/0
DATA0 (8): 80 06 01 03 09 04 ff 00 
ACK
IN: 0x01/0
DATA1 (38): 26 03 53 00 54 00 4d 00 69 00 63 00 72 00 6f 00 65 00 6c 00 65 00 63 00 74 00 72 00 6f 00 6e 00 69 00 63 00 73 00 
ACK
OUT: 0x01/0
DATA1: ZLP
ACK
SETUP: 0x01/0
DATA0 (8): 80 06 02 03 09 04 ff 00 
ACK
SOF #103
IN: 0x01/0
DATA1 (44): 2c 03 53 00 54 00 4d 00 33 00 32 00 20 00 56 00 69 00 72 00 74 00 75 00 61 00 6c 00 20 00 43 00 6f 00 6d 00 50 00 6f 00 72 00 74 00 
ACK
OUT: 0x01/0
DATA1: ZLP
ACK
SETUP: 0x01/0
DATA0 (8): 80 06 03 03 09 04 ff 00 
ACK
IN: 0x01/0
DATA1 (26): 1a 03 30 00 30 00 30 00 30 00 30 00 30 00 30 00 30 00 30 00 30 00 31 00 41 00 
ACK
OUT: 0x01/0
DATA1: ZLP
ACK
SETUP: 0x01/0
DATA0 (8): 00 09 01 00 00 00 00 00 
ACK
IN: 0x01/0
DATA1: ZLP
ACK
SETUP: 0x01/0
DATA0 (8): a1 21 00 00 00 00 07 00 
ACK
IN: 0x01/0
DATA1 (7): 00 00 00 00 00 00 00 
ACK
OUT: 0x01/0
DATA1: ZLP
ACK
SOF #104
OUT: 0x01/1
DATA0 (64): fa fd 70 ff 22 00 00 00 00 40 06 60 09 98 08 1c 0c 1e 00 24 00 00 00 00 00 0a 00 64 00 82 00 fd 02 e8 03 e8 03 19 00 c8 00 0c 00 3c 00 3c 00 d8 00 d8 00 5b 00 5b 00 5b 00 00 00 00 00 00 00 00 
ACK
OUT: 0x01/1
DATA1 (48): 00 00 00 1e 00 1e 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 ad 00 8a 00 1e 19 01 01 04 00 04 04 02 02 02 00 00 00 00 00 01 00 00 fe 17 
NAK
OUT: 0x01/1
DATA1 (48): 00 00 00 1e 00 1e 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 ad 00 8a 00 1e 19 01 01 04 00 04 04 02 02 02 00 00 00 00 00 01 00 00 fe 17 
NAK
OUT: 0x01/1
DATA1 (48): 00 00 00 1e 00 1e 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 ad 00 8a 00 1e 19 01 01 04 00 04 04 02 02 02 00 00 00 00 00 01 00 00 fe 17 
NAK
```

Exchange with assigned temperatures and actual temperatures:

```
OUT: 0x01/1
DATA0 (64): ff fd 5a ff 21 00 00 00 00 00 00 00 00 
d0 07 d0 07 d0 07 d0 07 
// Consigne K1, K2, K3, K4 toutes à 20°C
00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 
bd 07 d0 07 e9 07 46 08 
// Températuel K1 -> bd 07 (19,81°C), K2 -> d0 07 (20,00°C), K3 -> e9 07 (20,25°C), K4 -> 46 08 (21,18°C)
00 00 00
```

# Hardware

Serial Port 

> lsusb -v -d 0483:5740

Bus 001 Device 003: ID 0483:5740 STMicroelectronics Virtual COM Port
Couldn't open device, some information will be missing
Negotiated speed: Full Speed (12Mbps)
Device Descriptor:
  bLength                18
  bDescriptorType         1
  bcdUSB               2.00
  bDeviceClass            2 Communications
  bDeviceSubClass         2 Abstract (modem)
  bDeviceProtocol         0 
  bMaxPacketSize0        64
  idVendor           0x0483 STMicroelectronics
  idProduct          0x5740 Virtual COM Port
  bcdDevice            2.00
  iManufacturer           1 STMicroelectronics
  iProduct                2 STM32 Virtual ComPort
  iSerial                 3 00000000001A
  bNumConfigurations      1
  Configuration Descriptor:
    bLength                 9
    bDescriptorType         2
    wTotalLength       0x0043
    bNumInterfaces          2
    bConfigurationValue     1
    iConfiguration          0 
    bmAttributes         0xc0
      Self Powered
    MaxPower              100mA
    Interface Descriptor:
      bLength                 9
      bDescriptorType         4
      bInterfaceNumber        0
      bAlternateSetting       0
      bNumEndpoints           1
      bInterfaceClass         2 Communications
      bInterfaceSubClass      2 Abstract (modem)
      bInterfaceProtocol      1 AT-commands (v.25ter)
      iInterface              0 
      CDC Header:
        bcdCDC               1.10
      CDC Call Management:
        bmCapabilities       0x00
        bDataInterface          1
      CDC ACM:
        bmCapabilities       0x02
          line coding and serial state
      CDC Union:
        bMasterInterface        0
        bSlaveInterface         1 
      Endpoint Descriptor:
        bLength                 7
        bDescriptorType         5
        bEndpointAddress     0x82  EP 2 IN
        bmAttributes            3
          Transfer Type            Interrupt
          Synch Type               None
          Usage Type               Data
        wMaxPacketSize     0x0008  1x 8 bytes
        bInterval              16
    Interface Descriptor:
      bLength                 9
      bDescriptorType         4
      bInterfaceNumber        1
      bAlternateSetting       0
      bNumEndpoints           2
      bInterfaceClass        10 CDC Data
      bInterfaceSubClass      0 [unknown]
      bInterfaceProtocol      0 
      iInterface              0 
      Endpoint Descriptor:
        bLength                 7
        bDescriptorType         5
        bEndpointAddress     0x01  EP 1 OUT
        bmAttributes            2
          Transfer Type            Bulk
          Synch Type               None
          Usage Type               Data
        wMaxPacketSize     0x0040  1x 64 bytes
        bInterval               0
      Endpoint Descriptor:
        bLength                 7
        bDescriptorType         5
        bEndpointAddress     0x81  EP 1 IN
        bmAttributes            2
          Transfer Type            Bulk
          Synch Type               None
          Usage Type               Data
        wMaxPacketSize     0x0040  1x 64 bytes
        bInterval               0
