# CheatNeutraliser

Advanced packet-aware Minecraft anti-cheat/cheat-neutralisation plugin for Paper 1.21.x, designed around Paper 1.21.11 and Java 21.

## Architecture note

A server cannot literally inspect arbitrary client source code or prove that a packet came from an unmodified Minecraft client. PacketEvents exposes the protocol representation received by the server. CheatNeutraliser therefore validates protocol structure, packet size, packet ordering, and traffic rates instead of pretending it can inspect client binaries.

The hot path is intentionally small: deterministic safety checks run before normal packet handling, while scoring and correlation run on a bounded asynchronous executor. Bukkit/Paper API calls are not made from async analysis tasks.

The plugin does **not kick players**. Its default response is to cancel unsafe packets and continue monitoring.

## GitHub Codespaces terminal

```bash
sudo apt-get update
sudo apt-get install -y openjdk-21-jdk
chmod +x gradlew
./gradlew clean build
```

The shaded plugin JAR is produced at:

```text
build/libs/cheatneutraliser-1.0.0.jar
```

Copy that JAR into the server's `plugins/` directory.

## Commands

```text
/cn status
/cn reload
/cn debug
```

Permission: `cheatneutraliser.admin`

## Compatibility

Compiled against Paper 1.21.11. The implementation uses the stable Paper 1.21 API surface and is intended for Paper 1.21.1 through 1.21.11. For production servers, test the exact Paper build you deploy because protocol and server internals can change between patch releases.

## Design goals

- No kick/ban enforcement.
- Synchronous, allocation-light packet safety gate.
- Bounded asynchronous analysis workers.
- Per-player traffic windows.
- Packet-size and burst protection.
- Immutable snapshots passed to async workers.
- No Bukkit access from async analysis.
- Conservative neutralisation rather than punishment.
