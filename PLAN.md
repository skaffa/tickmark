# Tickmark: plan

Tickmark is a client-side Fabric mod for Minecraft 26.3 that measures farm output: items per hour, per item, at a
marked output (container, container group or box area), with a trend and a trust level.

Status: M1 done 2026-11-12 (skeleton, core maths, 34 unit tests). M2 next, waiting for the go.

## 0. Name

- **Tickmark** (id `tickmark`, package `dev.tickmark`, command `/tickmark` with short alias `/tm`,
  files in `config/tickmark/`). Chosen 2026-11-12. "Tick marks" are the tally strokes in the icon, and
  rates run on the game *tick* clock.
- Earlier names: Tally (Modrinth slug taken by a survival advancement pack that may also use the mod
  id `tally` and a `/tally` command), then Tallymark (free, but the user wanted a better name).
- Modrinth slug `tickmark` (no project titled Tickmark either) and GitHub `skaffa/tickmark` were free
  on 2026-11-12. The project folder stays `C:\Users\henk\Documents\tally` as the prompt asked.

## 1. Facts checked in 26.3 code

All checked with `javap` on `minecraft-{common,clientonly}-deobf-26.3.jar`.

| Fact | Evidence | Consequence |
|---|---|---|
| Entity removal reasons exist only on the server: `KILLED, DISCARDED, UNLOADED_TO_CHUNK, UNLOADED_WITH_PLAYER, CHANGED_DIMENSION`. | `Entity$RemovalReason` | The client never learns *why* an entity left. |
| `ClientboundRemoveEntitiesPacket` carries only `IntList entityIds()`. No reason. | record fields | Hopper pickup, despawn, merge, burning, void and leaving tracking range all look the same. Tickmark must infer the cause. |
| Hopper / hopper-minecart pickup: `HopperBlockEntity.addItem(Container, ItemEntity)` copies the stack, inserts it, then `setItem(rest)` (partial) or `discard()` (all). No take packet. | bytecode of `addItem` | Partial pickup shows as a count drop on the entity (synced `DATA_ITEM`); full pickup shows as plain removal. |
| `ClientboundTakeItemEntityPacket(itemId, playerId, amount)` is built only in `LivingEntity.take`. | only constructor call site | Pickup by the player, mobs, allays, etc. is announced with the collector's id and amount. Tickmark can subtract those exactly (not farm output to a container), or count them in the "player inventory" mode. |
| Item merging: `ItemEntity.tryToMerge` / `merge(...)` raises one stack's count (synced via entity data) and discards the other. | `ItemEntity` methods | Conservation: total count of items in the area stays equal across a merge. Count by *sum of stack sizes*, not by entity count. |
| `ItemEntity.age` is private and not synced; the client starts its own copy at spawn on the client. `LIFETIME` constant exists (6000 ticks). | fields | Client age is only a lower bound. Despawn guess: removed, client-age ≥ ~5900, no container near. |
| Game time: `MinecraftServer` calls `forceGameTimeSynchronization()` when `tickCount % 20 == 0`, sending `ClientboundSetTimePacket(overworld gameTime, clocks)`. | bytecode of `tickServer` | Each packet = 20 server ticks. Server TPS = Δgame time / Δwall time between packets. Real lag detection without a server mod. |
| `ClientLevel.tickRateManager()` (`tickrate()`, `isFrozen()`), updated by `ClientboundTickingStatePacket` / `ClientboundTickingStepPacket`. | `ClientLevel`, `ClientPacketListener` | Detect `/tick rate` and `/tick freeze`; a frozen span is a gap, not zero output. |
| `ClientLevel.hasChunk(x, z)`; `ClientPacketListener.handleForgetLevelChunk` / `handleLevelChunkWithLight`. | method lists | Track per counter whether its chunks were present on the client. |
| Container contents arrive only through `ClientboundContainerSetContentPacket` / `...SetSlotPacket` while the menu is open. `handleBlockEntityData` does not carry chest items. | packet handlers; Gauge experience | Unopened containers are invisible in multiplayer (as the prompt says). |
| Player inventory: `handleContainerSetSlot` (menu 0 / inventory), `handleSetPlayerInventory`, `handleSetCursorItem`. | `ClientPacketListener` | Inventory gains are visible exactly. |
| Integrated server: `mc.getSingleplayerServer()` → `IntegratedServer`; `isPaused()`, `getTickCount()`, `tickRateManager()`, `getAverageTickTimeNanos()`, `server.execute(...)`, `getLevel(dim)`. | `IntegratedServer`, `MinecraftServer` | Gauge's ServerPeek pattern works: read containers on the server thread, publish an immutable snapshot. |

Checked in M1 (2026-11-12):
- Item entities: `EntityTypes.ITEM` uses `clientTrackingRange(6)` (6 chunks = 96 blocks, further
  capped by the server's view distance and `entity-broadcast-range-percentage`) and
  `updateInterval(20)`. Entity mode works only while the player is within that range of the counter;
  Tickmark uses 80 blocks as the safe limit and treats removals beyond it as "unknown".
- Simulation distance: `ClientPacketListener.handleSetSimulationDistance` stores it;
  `ClientLevel.getServerSimulationDistance()` reads it. A chunk the client has but outside simulation
  distance does not tick, so the farm stops: treat it like "not loaded".
- Integrated server ticking: `ServerLevel.isPositionEntityTicking(BlockPos)`,
  `isPositionTickingWithEntitiesLoaded(long)`, `shouldTickBlocksAt(long)`, `areEntitiesLoaded(long)`
  and `getForceLoadedChunks()` all exist. Exact mode asks the server directly whether the counter's
  chunks are entity-ticking (covers `/forceload` and ender-pearl loading).

## 2. What the client can and cannot see

| Situation | What Tickmark sees | Method | Trust |
|---|---|---|---|
| Singleplayer / LAN host | Real container contents on the server thread | **Exact** (`server`) | Exact |
| Multiplayer, items fall into the area as entities | Item entities, their stack sizes, their removal | **Entities** (`entity`) | Estimate, usually within a few % |
| Multiplayer, player collects (AFK fish / XP / raid farm) | Player inventory changes, take packets | **Inventory** (`inv`) | Exact for what the player picks up |
| Multiplayer, output goes straight into a container (hopper chain from a closed system, no visible item entities) | Nothing until the player opens it | **Snapshots** (`open`) | Exact totals between opens, no detail inside |
| Chunks not loaded on the client, or outside simulation distance | Nothing | gap | Period left out |

Things Tickmark will never see in multiplayer: items moved hopper-to-hopper inside closed containers,
items created directly in a container (e.g. a crafter or a composter output without entities),
and anything beyond the client's entity tracking range.

## 3. Counting methods

### 3.1 Exact (singleplayer and LAN host). Default wherever available.
- Every N game ticks (default 20) the client sends one `server.execute` task (as Gauge's
  `ServerPeek`): for each counter in the player's dimension, read each container through the block
  (`ChestBlock.getContainer` for double chests, like a comparator) and sum counts per item.
- Box areas: also sum item entities inside the box (`level.getEntitiesOfClass(ItemEntity, box)`) on
  the server, so a collection area works in exact mode too.
- **Gain** for a sample = max(0, now − before) per item. Items leaving the counter (a player emptying
  it, a hopper draining it) must not turn into negative output: a drop resets that item's baseline.
  An optional "net" mode keeps the signed value for storage-flow use.
- A full container stalls a farm: detect "all slots full or no room for the last-seen items" and flag
  it in the HUD ("full, rate is capped").
- Server clock: sample stamp = `overworld.getGameTime()` read on the server thread, so pause, lag and
  `/tick freeze` are exact.
- Chunk check: `level.isPositionEntityTicking(pos)` / block-ticking check on the server. Not ticking
  = gap.

### 3.2 Entities (multiplayer)
State per counter: a map `entityId → (item, count, lastPos, firstSeenTick)` for item entities in or
near the box (box grown by 1.5 blocks so items falling in are caught).

Events and rules:
- **Enter**: an entity appears in the box (spawned inside or moved inside). Record it. Not counted yet.
- **Count change** (entity data update): drop in count = partial pickup by a hopper, if a hopper or
  hopper minecart is adjacent; rise = merge (another entity's removal pays for it).
- **Removal** (`handleRemoveEntities`, read before the entity is gone, via a mixin at HEAD):
  - A take packet for this id arrived in the same tick → collected by that living entity. If it is
    the local player: counted only by the inventory method; otherwise "taken by mob/player", not
    output to the counter.
  - It merged: a nearby entity of the same item gained exactly that count in the same or next tick →
    not counted (conservation). Settle merges with a 2-tick window before deciding.
  - Chunk forgotten or entity out of tracking range (player moved, distance to entity ≥ tracking
    range − margin) → **unknown**, not counted; marks a gap.
  - Within 1 block above / beside a hopper, hopper minecart under the box, or the box's marked
    containers → **collected**, counted.
  - Client-age ≥ 5900 ticks and nothing else fits → **despawned**, counted as "lost" (shown, not in
    the rate).
  - Otherwise → **removed, cause unknown** (lava, cactus, void): shown as "lost".
- What counts as **output** is configurable per counter:
  - *Collected* (default for hopper-fed counters): items that disappear into a hopper.
  - *Arrived* (for open collection areas without hoppers): items that enter the box, counted once
    per entity id (merges handled by conservation).
- Tests on the pure logic: merge chains, partial pickups, re-entering the same entity, a player
  stealing from the area, despawn, unload during a sample.

### 3.3 Inventory (multiplayer, player collects)
- Diff the player inventory (all 36 slots + offhand + cursor) every tick, but only count gains that
  match a take packet with `playerId == local player` in the same tick (so items moved from a chest
  or crafted are not counted). Amount from the take packet.
- Option "count all gains" for farms that put items straight into the inventory (rare).
- A per-counter item filter (e.g. only `rotten_flesh`, `gold_nugget`) because the player collects
  everything around them.

### 3.4 Snapshots (multiplayer, opened containers)
- When the player opens a marked container, record the full contents (as Gauge does) with the game
  time. Gain between two opens = exact output for that span, if nothing was taken out in between by
  the player (Tickmark sees the player's own clicks, so it can subtract them).
- Shown as a separate line "last checked 12 min ago: 1,284 iron/h (exact)" next to the live entity
  estimate. When both exist, the snapshot corrects the entity estimate's drift in the HUD.

### 3.5 Method selection
- Singleplayer / LAN host → Exact.
- Multiplayer → Entities if the counter has a box or a hopper is in or under it, plus Snapshots for
  marked containers, plus Inventory if the player marks themself (`/tickmark mark self`).
- The HUD shows the method as a tag: `EXACT`, `ENTITY ~`, `INV`, `SNAPSHOT`, and a trust dot:
  green = exact, yellow = estimate with no unknown removals, orange = unknowns or gaps > 10% of the
  window.

## 4. Clock, lag and gaps

- **Sample clock = game time.** Exact mode: server `getGameTime()`. Multiplayer: the client level's
  game time, which follows `ClientboundSetTimePacket` (every 20 server ticks).
- **Rate at 20 TPS** = items / (game ticks / 72,000) per hour. This is the farm's real per-tick yield.
- **Real-time rate** = items / wall hours. Shown only when they differ by more than 5%.
- **Server TPS estimate** (multiplayer): Δgame time / Δwall between time packets, smoothed over
  30 s. Exact mode: from the integrated server's tick times. The HUD notes "server ran at 14.2 TPS:
  real output 29% lower".
- `/tick rate` and `/tick freeze`: frozen spans are gaps. Ticks still count normally at any rate.
- **Gaps**: a span is a gap when any counter chunk was missing on the client, outside simulation
  distance (multiplayer), not entity-ticking (exact mode), the world was paused, or the player was in
  another dimension. Gaps are left out of both items and time, and drawn as breaks in the sparkline.
  The HUD shows "measured 47 min of 1 h 05 (18 min not loaded)".

## 5. Rate maths (in `core`, Minecraft-free, unit-tested)

- `Sample(gameTick, wallMillis, Map<itemId, gain>, flags)` in a ring buffer per counter, bucketed per
  game minute (1,200 ticks) to bound memory: 24 h = 1,440 buckets per counter.
- **Window rate** over 5 / 15 / 60 min (setting) and **total rate** since reset; only non-gap ticks
  count as time.
- **Trend**: least-squares slope of per-bucket rates over the window, normalised by the mean;
  |change over the window| < 10% of the mean → stable (10% so normal farm noise does not flip it; tunable), else rising / falling. Needs ≥ 5 buckets, else "warming up".
- **Burstiness**: farms like raid farms or iron farms produce in bursts. Show the coefficient of
  variation; a high value makes the window rate less trustworthy and the HUD says "bursty: use the 60
  min window".
- **Confidence** for estimates: lost / unknown items as a share of counted items.
- CSV writer: `time_iso,game_tick,item,count,method,gap` per bucket, RFC 4180 quoting.

## 6. Data and persistence

- A counter: id (short number), name, dimension, kind (`container`, `group`, `box`, `self`),
  positions or AABB, output rule, item filter, created/paused flags, the bucket series.
- Stored per world: `config/tickmark/worlds/<world-key>/counters.json`.
  - World key: singleplayer save folder name; multiplayer server address (`host_port`), as other mods.
  - Saved on leave, every 5 min, and on change. Restored on join; the time away is a gap.
- Exports: `config/tickmark/exports/<world>-<counter>-<yyyyMMdd-HHmmss>.csv`.
- Settings: `config/tickmark/config.json` via a `ConfigStore` copied from Vire.

## 7. Player interface

### Marking
- Key **U** (rebindable): mark the container under the crosshair; it joins the group of the counter
  you marked last if it is adjacent (chest walls); hold Shift to always start a new counter.
- `/tickmark mark` (looked-at block), `/tickmark mark box <x1 y1 z1> <x2 y2 z2>` and a two-click box mode
  (`/tickmark mark box` then U on two corners), `/tickmark mark self`.
- `/tickmark name <id> <name>`, `/tickmark reset [id|all]`, `/tickmark pause [id|all]`,
  `/tickmark list`, `/tickmark remove <id>`, `/tickmark export [id|all]`, `/tickmark config`, `/tickmark help`.
- Key **I** (rebindable): toggle HUD. Both U and I are free in vanilla 26.3 and in the user's other
  mods (they use B G H J K M N R V Y Z).

### HUD panel
```
Tickmark #2 Iron farm        EXACT ●
iron_ingot   1,284/h   +2,140  ▁▂▄▅▄▆▅  stable
poppy           62/h      +103  ▁▁▂▁▁▂▁  stable
measured 1 h 40 m · 60 m window
```
Lines for an estimate add `~` and a footer such as `14 lost · 3 unknown · 18 m not loaded`.
Settings: position (corner + offset), which counters (nearest / pinned / all), max items per counter,
which items (all / top N / filter), window, unit (per minute / hour / day), sparkline on/off.

### World render
- Outline of each counter's containers or box, and a label above it with the top item rate and the
  method tag, using vanilla `Gizmos` submitted from `END_CLIENT_TICK` as Gauge does.
- Colour by trust (green / yellow / orange); grey when paused or in a gap.

### Settings screen
Vanilla widgets, tooltips on every option, from Mod Menu (optional `modmenu` entrypoint, `suggests`)
and `/tickmark config`. Code copied from Vire's `ConfigScreen`.

## 8. Testing

- **Unit tests (JUnit 5, `core`)**: windows, gaps excluded from time and items, lag correction
  (20 TPS vs real rate), trend classification, burstiness, bucket roll-over, entity-event state
  machine (merge, partial pickup, take packet, unload, despawn), CSV quoting and columns, config and
  counter JSON round-trip.
- **In-game self-test** (`-Pselftest=<mode>`, copied from Vire's `dev/SelfTest.java`):
  - Rig built with commands: a dispenser with a known stack on a fixed clock (repeating command
    block or `/tick`-independent observer clock, period P ticks) shooting one item at a time onto a
    hopper into a chest. Known rate = 72,000 / P items per hour.
  - `exact`: singleplayer world, counter on the chest, wait until ≥ 200 items or a timeout, pass when
    the measured rate is within **±2%** of the known rate.
  - `entity`: same rig on the test server (`run-server/` copied from Gauge), counter as a box over
    the hopper, pass within **±5%**. Also a `/tick rate 10` phase: the 20-TPS rate must stay within
    ±5% while the real-time rate halves.
  - `gap`: teleport away until the chunks unload, come back; the rate must not drop by more than the
    tolerance and the gap must be reported.
  - Conditions, not fixed tick counts; logs `SELFTEST PASS/FAIL`, then quits.
- Screenshots of the HUD and world labels (DevShots pattern from Gauge), looked at before claiming a
  visual feature works.

## 9. Milestones

Stop and report after each.

1. **M1 Skeleton and verification.** Gradle project from Gauge/Vire (JDK 25, Loom 1.19-SNAPSHOT,
   Fabric API 0.165.2+26.3), icon, CI workflow, git repo. Verify the open items in section 1
   (tracking range, simulation distance packet, server chunk ticking). `core` rate maths + CSV +
   unit tests.
2. **M2 Exact mode.** Counters (container, group, box), marking key and commands, ServerPeek-style
   reads, persistence per world, basic HUD. Self-test `exact`.
3. **M3 Multiplayer methods.** Entity tracking with mixins on `ClientPacketListener`
   (`handleRemoveEntities`, `handleTakeItemEntity`, entity data), inventory method, open-snapshots,
   method tags and trust. Test server rig; self-test `entity` and `/tick rate` phase.
4. **M4 Clock, lag and gaps.** TPS estimate, 20-TPS vs real rate, chunk / simulation distance /
   pause gaps, sparkline breaks. Self-test `gap`.
5. **M5 World render, settings, export.** Gizmos outlines and labels, settings screen + Mod Menu,
   `/tickmark export`. Screenshots checked.
6. **M6 Delivery.** README (with the limits table from section 2), CHANGELOG, `media/modrinth-
   description.md`, gallery 1600×900 + `captions.md`. Ask before creating the GitHub repo or pushing.

## 10. Open questions for the user

1. Exact mode output rule: count only gains (default) or also offer a net in/out mode for storage
   systems?
2. Keys U (mark) and I (HUD): fine?
