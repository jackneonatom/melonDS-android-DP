# PC melonDS patch

Same Wi-Fi fixes for desktop melonDS (`src/net/LAN.cpp`, `src/net/LAN.h`), made against
melonDS master (906e9eb, Aug 2026). Stock PC melonDS 1.x already interoperates with the
Android build; applying this to the PC side as well gives the best results.

```
git clone https://github.com/melonDS-emu/melonDS.git
cd melonDS
git am /path/to/pc-melonDS/*.patch
```
Then build as usual (see melonDS's BUILD.md).
