# Changelog

## 0.1.0 (not released yet)

First version.

- Counters: a container, a group of containers (chest walls, double chests as one), a box area, or
  your own pickups (`/tickmark mark self`). Marked with U or `/tickmark mark`, kept per world.
- Exact counting in singleplayer and on a LAN host: the containers are read on the built-in server.
- On servers: counting from item entities (hopper pickups, merges, player and mob pickups and
  despawns told apart), and an exact check from opening a marked container twice.
- Rates per minute, hour or day at 20 TPS from the game clock; real-time output when the server
  lags; gaps (unloaded chunks, pause, `/tick freeze`, out of sight) left out instead of counted as
  zero.
- HUD panel: rate, total, sparkline and trend per item, method tag and trust colour, notes for gaps,
  full containers, lag and bursty farms. World outlines and rate labels.
- Settings screen (Mod Menu or `/tickmark config`), `/tickmark list | name | reset | pause | resume |
  remove | pin | filter | rule | export | hud`, `/tm` alias, CSV export.
