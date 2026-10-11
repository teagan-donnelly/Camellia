# Camellia Smart Wristband — Proposed Hardware Architecture

**Status:** proposed architecture. No hardware has been built or tested. Camellia currently uses a **Java simulator**
(`backend/src/main/java/com/camellia/monitoring/MonitoringService.java`) that generates synthetic observations and
stores them through the same backend path a real gateway would use. Every capability, figure and compatibility claim
below needs validation before it is relied on. Nothing here is a medical device, and nothing has clinical validation.

Labels used in this document:
- **Fact** — documented property of the named part or protocol.
- **Assumption** — a design choice or expectation that has not been tested.
- **Unverified** — a claim that must be checked against datasheets, platform documentation or measurement.

## 1. Purpose and limits

The wristband supports nursing review during a hospital stay: estimated walking and movement, heart rate, SpO₂ and
peripheral skin temperature, plus device health. It produces **review notifications** for nurses. It does not diagnose,
detect falls, prove that a patient was repositioned, or replace nursing assessment.

What a wrist sensor can and cannot establish:

| Question | Wrist IMU + optical sensor | Notes |
|---|---|---|
| Arm or body movement occurred | Directly measurable (acceleration, rotation) | Fact |
| Walking (step-like gait pattern) | Inference from IMU signal | Assumption — needs a validated classifier; can confuse arm motion with walking |
| Walking duration | Inference | Same caveat |
| Patient was repositioned in bed | **Cannot be established** | A nurse turning a patient may barely move the wrist; arm movement happens without repositioning. Camellia counts only nurse-documented repositioning. |
| Fall | **Not claimed** | Fall detection needs validation and is out of scope |
| Heart rate | Measurable with PPG at rest | Unverified accuracy at the wrist; motion artifacts degrade it |
| SpO₂ | Estimated with red/IR PPG | Unverified at the wrist; skin tone, perfusion, fit and motion affect accuracy; would need clinical validation |
| Skin temperature | Needs a dedicated temperature sensor | The MAX30102's internal temperature reading is the chip's die temperature, used for compensation — not skin temperature |
| Inactivity | **Only when the device is connected and reporting** | Missing data is a device problem, never evidence of inactivity |

## 2. The four layers

```
 ┌──────────────── WRISTBAND ────────────────┐      ┌───────── GATEWAY (per unit) ─────────┐      ┌──── CAMELLIA ────┐
 │ SENSE                                      │      │ COMPUTE (proposed QNX-based SBC)     │      │ Java Spring Boot │
 │  MPU-6050 6-axis IMU ─┐                    │ BLE  │  BLE central: pairs bands, receives  │HTTPS │  /api/monitoring │
 │  MAX30102 PPG (HR/SpO₂)├─ INTERFACE: I²C ──┤─────►│  buffered batches, validates,        │/MQTT │  (encounter-     │
 │  Skin temp sensor*    ─┘                    │ GATT │  timestamps, forwards                │─────►│   scoped)        │
 │ COMPUTE: ESP32 (sampling, features,         │      │ CONNECT: Wi-Fi/Ethernet to backend   │ TLS  │  Snowflake       │
 │          buffering, BLE)                    │      │  (via a broker or HTTPS)             │      │                  │
 └─────────────────────────────────────────────┘      └──────────────────────────────────────┘      └──────────────────┘
   * sensor choice open (assumption)
```

### 2.1 Sense
- **MPU-6050** (Fact): 3-axis accelerometer and 3-axis gyroscope, I²C interface, on-chip FIFO and a digital motion
  processor. Use: movement, orientation changes and step-like patterns. Walking classification and "possible position
  change" are **inferences** (Assumption).
- **MAX30102** (Fact): integrated red and infrared LEDs with a photodetector for photoplethysmography (PPG), I²C interface.
  Use: heart rate and an SpO₂ estimate. Wrist placement, motion, skin tone and perfusion affect PPG quality (Unverified
  accuracy for this design).
- **Skin temperature** (Assumption): a separate digital temperature sensor or thermistor on the skin-facing side. Part not
  yet selected. It measures peripheral skin temperature, not core body temperature.

### 2.2 Interface
- Both sensors on one **I²C** bus (Fact: both are I²C devices with different default addresses — verify against the
  chosen breakout boards and any address-select pins).
- MPU-6050 interrupt pin to an ESP32 GPIO for motion/FIFO events; MAX30102 interrupt pin for "FIFO almost full"
  (Assumption: interrupt-driven sampling to save power).
- Bus speed, pull-ups and cable lengths follow the breakout boards' guidance (Unverified for the final layout).

### 2.3 Compute
- **Wristband MCU: ESP32** (Fact: dual-mode Wi-Fi + Bluetooth SoC; specific variants such as ESP32, ESP32-S3 or ESP32-C3
  differ in cores and radio features). Responsibilities (Assumption): sensor sampling, simple feature extraction (step-like
  counts, movement summaries, PPG windows), local timestamps, a ring buffer for offline periods, BLE GATT server.
- **Gateway: proposed QNX-based single-board computer.** QNX Neutrino is a real-time OS for ARM and x86 processors; it does
  not run on the ESP32, which would typically run FreeRTOS through ESP-IDF. Running QNX on a specific gateway board, and the
  availability of a BLE stack and drivers for that board under QNX, are **Unverified** and must be confirmed with QNX
  platform documentation (board support packages) before committing to hardware.
- Gateway responsibilities (Assumption): pair and authenticate bands, receive batches, attach gateway receive time,
  validate payloads, persist locally while the backend is unreachable, forward to Camellia.

### 2.4 Connect
- **Band → gateway: Bluetooth Low Energy (GATT)** (Assumption). Bands notify batches; the gateway acknowledges sequence
  numbers so lost batches are re-sent.
- **Gateway → backend: HTTPS (REST) or MQTT over TCP/TLS** via a broker. MQTT does **not** run over BLE; a gateway (or
  another bridge) terminates BLE and publishes over IP.
- **Backend:** Camellia's Java backend validates every batch against an **active monitoring session**, which binds a
  device to exactly one patient and one hospital encounter. Observations for a device without an active session are
  rejected, so data can never be attached to the wrong stay.

## 3. Patient–device–encounter association

1. At admission (or when monitoring is ordered), a nurse scans the band's ID and the patient's hospital wristband
   (Assumption: barcode/QR on both).
2. Camellia opens a **monitoring session**: `patient + encounter + device + start time` (`MONITORING_SESSIONS`).
3. Every observation carries the session's encounter id (`SENSOR_OBSERVATIONS.ENCOUNTER_ID`). Queries always filter by
   patient **and** encounter, so October readings can never appear under a January or March encounter.
4. At discharge or when the band is removed, the session is stopped; later data from that band is rejected until a new
   session is opened.

The simulator follows exactly this model: sessions are created for specific inpatient encounters, and each simulated
reading is stored with that encounter id.

## 4. Sample payloads (proposed)

Band → gateway (BLE notification, compact JSON or CBOR — Assumption):
```json
{ "dev": "WB-SIM-0001", "seq": 18422, "t0": 1791651600, "hz_imu": 25,
  "hr": [[1791651600, 88, "G"]], "spo2": [[1791651600, 95.4, "G"]], "skin_c": [[1791651600, 33.6]],
  "act": [{ "type": "WALKING", "start": 1791650880, "end": 1791651240, "conf": 0.71 }],
  "batt": 62.5 }
```

Gateway → Camellia (HTTPS POST or MQTT publish; the shape Camellia's model stores):
```json
{
  "gatewayId": "GW-4WEST-01",
  "receivedAt": "2026-10-11T18:00:03Z",
  "deviceId": "WB-SIM-0001",
  "sequence": 18422,
  "observations": [
    { "type": "HEART_RATE", "value": 88, "unit": "bpm", "observedAt": "2026-10-11T18:00:00Z", "quality": "GOOD", "source": "DEVICE" },
    { "type": "SPO2", "value": 95.4, "unit": "%", "observedAt": "2026-10-11T18:00:00Z", "quality": "GOOD", "source": "DEVICE" },
    { "type": "SKIN_TEMP", "value": 33.6, "unit": "°C", "observedAt": "2026-10-11T18:00:00Z", "quality": "GOOD", "source": "DEVICE" }
  ],
  "events": [ { "type": "WALKING", "startedAt": "2026-10-11T17:48:00Z", "endedAt": "2026-10-11T17:54:00Z", "source": "SENSOR" } ],
  "battery": 62.5
}
```
Camellia resolves `deviceId` to the active session and stores rows in `SENSOR_OBSERVATIONS` and `ACTIVITY_EVENTS` with the
session's patient and encounter ids. The current prototype has no device-ingest endpoint; the simulator writes the same
rows directly from the backend.

## 5. Approximate bill of materials

| Item | Purpose | Cost |
|---|---|---|
| ESP32-family module or development board | MCU, BLE, buffering | Not quoted — obtain supplier quotes (Estimate pending) |
| MPU-6050 breakout | Movement / step-like patterns | Not quoted |
| MAX30102 breakout | PPG heart rate, SpO₂ estimate | Not quoted |
| Skin temperature sensor (part TBD) | Peripheral temperature | Not quoted |
| Rechargeable Li-ion/LiPo cell + charger/protection IC | Power | Not quoted |
| Enclosure + strap, skin-contact window for PPG | Wearable housing | Not quoted |
| Gateway SBC (QNX compatibility to be confirmed) + power | BLE ↔ IP bridge | Not quoted |

Hobbyist breakout boards are suitable for a prototype only. A clinical device would need medical-grade components, a
custom board, biocompatible materials and regulatory work, at very different cost.

## 6. Power (no figures claimed)

Battery life has **not** been estimated or measured. Expected dominant consumers (Assumption): PPG LED drive current and
sampling rate, the BLE radio duty cycle, and MCU active time. Levers: duty-cycled PPG (e.g. a reading every N minutes
instead of continuous), IMU interrupt-driven wake-up, batched BLE notifications, low-power MCU sleep modes. Battery
capacity, average current and therefore runtime must be **measured** on the actual hardware. The simulator's battery
curve (~1.1 % per hour) is invented for the demo.

## 7. Connectivity failures and buffering

- Band ring buffer (Assumption: sized for several hours of summaries) keeps data while out of BLE range; batches carry
  sequence numbers; the gateway acknowledges, and the band deletes only acknowledged batches.
- Gateway store-and-forward while the backend is unreachable; idempotent upload keyed by device + sequence prevents
  duplicate rows.
- Camellia treats stale or missing data as a **device connectivity** review ("no readings received"), never as patient
  inactivity. The simulator demonstrates this (disconnect → device alert only).

## 8. Timestamp synchronization

- Gateway clock synchronized with NTP; the band's clock is set by the gateway at pairing and periodically corrected
  (Assumption).
- Each observation keeps the device timestamp; the gateway adds its receive time. Large offsets are flagged and the
  data is marked poor quality rather than silently corrected.
- Camellia stores UTC and displays hospital-local time (`CAMELLIA_HOSPITAL_TIME_ZONE`, default America/New_York).

## 9. Security considerations

- BLE pairing with LE Secure Connections and per-band identity; gateway accepts only enrolled bands (Assumption).
- Signed firmware and secure boot on the MCU (to verify for the chosen ESP32 variant).
- TLS from gateway to backend; per-gateway credentials (client certificates or tokens) stored in secure storage; no
  shared secrets in firmware.
- Minimal data on the band: device id and readings, no patient identifiers. The patient link exists only in Camellia's
  monitoring session.
- Backend authorization: monitoring data is readable only by roles with patient access, scoped per encounter (as in the
  current prototype).

## 10. Hardware limitations

- Wrist PPG is sensitive to motion, fit, skin tone, perfusion and ambient light; SpO₂ at the wrist is not a substitute
  for clinical pulse oximetry.
- IMU-based walking detection confuses arm movement with gait; it cannot confirm repositioning or detect falls reliably.
- BLE range and interference in hospital environments are unverified.
- Skin temperature reflects the environment and blood flow; it is not core temperature.

## 11. Clinical validation requirements (before any clinical use)

- Bench and clinical accuracy studies for heart rate and SpO₂ against reference devices across skin tones and
  perfusion states; motion-artifact characterization.
- Validation of any activity classification (walking duration) against observed ground truth in the target population.
- Usability and alarm-burden studies for the nursing review notifications; clinical governance of care-plan intervals.
- Electrical safety, biocompatibility, cybersecurity and applicable regulatory pathways for the jurisdiction.
- Data-protection review (agreements, retention, access) before any identifiable data is involved.

## 12. How the simulator maps to this design

| Hardware concept | Simulator implementation |
|---|---|
| Band + session | `MONITORING_DEVICES`, `MONITORING_SESSIONS` (device ↔ patient ↔ encounter) |
| PPG and temperature readings | `SENSOR_OBSERVATIONS` (HEART_RATE, SPO2, SKIN_TEMP, BATTERY), every 15 simulated minutes, `source = SIMULATED_DEVICE`, quality flag |
| IMU events | `ACTIVITY_EVENTS` with `source = SENSOR` (WALKING with duration, MOVEMENT, POSSIBLE_POSITION_CHANGE) |
| Nurse documentation | `NURSING_INTERVENTIONS` + `ACTIVITY_EVENTS` with `source = NURSE` |
| Connectivity loss | `CONNECTED = FALSE`, `LAST_SYNC_AT` stops advancing, DEVICE_DISCONNECTED event |
| Gateway clock | Per-session simulation clock (`SIM_CLOCK_AT`), advanced explicitly; seeded random generator for reproducible scenarios |
