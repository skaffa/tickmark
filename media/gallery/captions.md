# Modrinth gallery

Upload in this order. All 1600×900, taken by the automated in-game tests (`-Pselftest=shots`, `gap`
and `entity`) on a command-block test farm that makes exactly 3,600 iron ingots per hour.

| File | Title | Description |
|---|---|---|
| 1-hud.png (featured) | Items per hour | Three counters: a chest (3,589/h, stable, with a per-minute sparkline), an area around the hopper, and your own pickups. |
| 2-labels.png | In the world | Every counter gets an outline and a label with its top item's rate. |
| 3-server.png | On servers | Estimated from item entities (ENTITY ~, 3,590/h) and checked exactly by opening the chest twice (3,600/h). |
| 4-gap.png | Gaps, not zeros | Away from the farm its chunks unload: that time is left out of the rate instead of lowering it. |
| 5-settings.png | Settings | HUD corner, counters shown, window, unit and more, in Mod Menu or with /tickmark config. |
