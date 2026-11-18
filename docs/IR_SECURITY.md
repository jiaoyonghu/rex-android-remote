# Robot receiver safety contract — required before any ROS2 wiring

IR transport is one-way. NEC codes and IR retransmissions do **not** provide authentication, delivery acknowledgement, or reliable STOP delivery. 0x3B4C user code is not a security credential.

A real receiver must: (1) default to DRY_RUN, (2) reject unknown/unmapped/short/invalid NEC frames, (3) never bypass ros2_control preflight / safety limit checks, (4) not confuse command ACK with robot state, (5) separately gate ACTIVE / STAND and DISABLE, (6) have field-tested independent physical disable, (7) include loss-of-command and motion supervisor that safely halts motion on loss of communications, (8) log accepted commands, (9) reject unwanted NEC repeats, (10) not infer speaker identity from speech-to-text. These conditions are NOT implemented in this version.

User-requested 'continuous until STOP' over one-shot IR alone is unsafe: STOP may be missed and movement can persist indefinitely. The app encodes one-shot signals as requested but **does not drive motors** until a safe execution protocol is designed.
