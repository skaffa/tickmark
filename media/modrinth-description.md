**How much does your farm really make?** Tickmark measures it for you. Mark a farm's output chest, a chest wall, a hopper line or a collection area, and a small panel shows items per hour for every item, the total so far, a per-minute sparkline and whether the rate is rising, stable or falling. No more counting by hand with a timer.

Everything happens on your own computer: Tickmark needs nothing on the server and sends no packets of its own.

## Marking a farm

- Look at a chest, barrel, hopper or other container and press **U**. A container next to the one you marked last joins the same counter, so a whole chest wall is one counter. **Shift + U** starts a new one. Double chests count once.
- `/tickmark mark box` and **U** on two corners: count an area, such as a collection floor.
- `/tickmark mark self`: count what you pick up yourself, for AFK farms where you collect.
- Name, pin, pause, reset, filter to certain items, or remove counters with `/tickmark` (or `/tm`). Counters are kept per world and survive leaving and rejoining.

## Exact in singleplayer, careful on servers

A client is not told what is inside a container unless you have it open. Tickmark works around that and always shows which method a counter uses:

- **Singleplayer and LAN host (`EXACT`)**: the containers are read on the built-in server every second. Exact.
- **Servers (`ENTITY ~`)**: Tickmark watches the item entities around your farm's hoppers. Items a hopper collects are counted. Two stacks merging, items picked up by players or mobs, and items that despawn are told apart and not counted as output. In tests it was within 1% of the true rate.
- **Exact check on servers**: open a marked chest, close it, and open it again later. The difference between the visits is exact, and the panel shows it next to the estimate.

## Honest rates

- Rates are per hour **at 20 TPS**, from the game's own tick clock. If the server lags, the panel also says how much lower the real output was ("Server ran at 14.9 TPS: real output 26% lower").
- When the farm's chunks are not loaded, the game is paused or frozen, or the farm is out of your sight on a server, that time is a **gap**. It is left out of the rate instead of dragging it down, and shows as dots in the sparkline.
- Bursty farms (raid farms, iron farms) get a note to use the 60-minute window.

## More

- Outlines and a rate label on every counter in the world.
- Per minute, per hour or per day; 5, 15 or 60 minute window; HUD in any corner.
- `/tickmark export` writes a CSV per game minute and item for spreadsheets.
- Settings screen in Mod Menu (optional) or with `/tickmark config`.

## Limits

On servers Tickmark cannot see items moved between hoppers inside closed containers, items created straight in a container, or farms more than about 80 blocks from you. Use the open-twice check for those.

Client-side only. Requires Fabric API. Minecraft 26.3.
