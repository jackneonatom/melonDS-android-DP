# Wi-Fi local multiplayer (Download Play between devices)

This branch adds DS local wireless play — **DS Download Play** and multi-card play —
between two separate devices:

- Android ↔ Android
- Android ↔ PC melonDS (1.0 or newer; best with the PC patch in `pc-melonDS/`)

over the same Wi-Fi network or a **phone hotspot**.

## Setup

1. Install this build on both phones (it installs next to the official melonDS).
2. **Phone A:** turn on its hotspot. **Phone B:** connect to that hotspot.
   (Or put both on the same Wi-Fi — 5 GHz if possible.)
3. On both: ROM list → ⋮ → **Local multiplayer (Wi-Fi)**.
4. **Host** (the phone with the game): *Host session* → *Pick a game* → start the game
   and go to its Download Play / multiplayer menu.
5. **Other phone:** tap the host's session in the list (or type its address, shown on
   the host's lobby screen) → *Open DS Download Play*.

Download Play needs the DS **BIOS and firmware dumps** on the receiving device
(Settings → Emulation → custom BIOS/firmware), because it boots the real DS menu.

**With a PC:** in PC melonDS use *System → Multiplayer → Host LAN game* / *Join LAN game*.
The phone shows up as a normal LAN player.

### Tips for low latency
- Battery saver off on both devices.
- Start the session *before* launching the game.
- If a game shows "communication error", raise *Advanced → Longest wait for a reply*.

## What was wrong, and what changed

The DS wireless protocol expects a reply within a few hundred **micro**seconds. melonDS
runs all consoles in lockstep on emulated time, so network latency becomes a short stall
instead of a failure — *if* the network layer waits long enough and doesn't lose frames.
It did both:

| Problem | Fix |
|---|---|
| The Android app never selected a multiplayer backend: every DS wireless frame went to a dummy interface. | Platform MP calls are routed through a session manager backed by the LAN backend. |
| **ENet's congestion throttle silently drops unreliable packets whenever RTT rises.** All DS frames are unreliable, so every Wi-Fi latency bump discarded them — ~2.5% of replies lost even on a perfect link. (Affects PC melonDS too.) | Throttle pinned open on every peer; also tells stock peers to stop throttling toward us. |
| Fixed 25 ms reply wait, per packet; shorter than a single Wi-Fi spike. The PC "receive timeout" setting was ignored by LAN mode. | Adaptive deadline per exchange from measured round trips (RFC 6298 style) + decaying backoff, 25–200 ms by default; the setting is now the lower bound. |
| Queued frames older than 16 ms discarded. | Window scales with the wait. |
| Single lost packet = missed reply. | Optional duplicate frames with receiver-side dedupe, negotiated per peer so stock melonDS never gets duplicates. |
| Discovery only broadcast to 255.255.255.255, which a hotspot phone routes out its cellular link. | Also sent to each interface's own broadcast address. |
| Players joining after the host's game started never learned the host was ready ("host gone"). | Readiness re-sent to late joiners. |
| Two devices with the same firmware dump have the same console MAC; the "randomize MAC" option used unseeded `rand()`, so it produced the same "random" MAC on every device. | Each device gets a unique MAC during a session; randomizer properly seeded. |
| Android radio power-save adds 50–300 ms spikes. | Low-latency Wi-Fi lock + multicast lock while a session is active. |
| Link dropped if one side paused or sat in a menu for >5 s. | Background pump services the connection when no frames are running. |

The wire protocol is unchanged apart from one optional control command that older
versions ignore, so this build interoperates with stock PC melonDS in both directions.

## Measured results

Two real LAN backends driven like the DS Wi-Fi chip (host CMD → wait for reply → ACK,
both sides spending CPU time per emulated frame, 60 fps frame limiter), through a relay
that simulates Wi-Fi delay, latency spikes and loss. 20 s per run.

| Network | Build | fps | missed replies | worst run of misses |
|---|---|---|---|---|
| Good hotspot, 1 exchange/frame | stock | 56.4 | 10.1% | 6 |
| | **patched** | **58.7** | **0.34%** | **1** |
| Wi-Fi router, 2 exchanges/frame | stock | 48.5 | 5.2% | 4 |
| | **patched** | **52.9** | **0.57%** | **1** |
| Hotspot, low-latency, 2/frame | stock | 31.9 | 11.7% | 4 |
| | **patched** | 30.6 | **1.4%** | **1** |
| Busy hotspot / power-save | stock | 22.7 | 36.2% | 15 |
| | **patched** | 15.5 | **3.1%** | **2** |
| Bad link | stock | 18.4 | 66.9% | 61 |
| | **patched** | 6.9 | **19.6%** | **5** |

Long runs of consecutive misses are what make a DS game give up with "communication
error"; the patched build stays at 1–5 even on a bad link. Mixed setups work: patched
host + stock client performed like patched/patched, and a stock host improved from 10.9%
to 2.9% misses with a patched client.

### The honest limit
Lockstep means each wireless exchange costs one network round trip. Speed is roughly
`1000 / (emulation time per frame + exchanges per frame × round trip)` fps. On a good
5 GHz hotspot (2–5 ms round trips) games doing one exchange per frame run at full speed;
games that do several per frame, or weaker links, run slower rather than disconnecting.
Matching a real DS's sub-millisecond radio would need speculative execution with
rollback, which isn't attempted here.

Bluetooth isn't supported: its 20–40 ms+ round trips and low throughput (Download Play
sends the whole game binary) make it strictly worse than a hotspot.
