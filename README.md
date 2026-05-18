# Tellus Alpine Climate

Altitude-aware seasonal snow + temperature for Minecraft 1.21.1 (NeoForge).
Built for real-Earth worldgen packs where seasonal snow should depend on the
mountain, not on which player happens to be standing nearby.

## What it does

- **Global snow placement.** Every loaded chunk is processed on a budgeted
  round-robin. There's no per-player radius — Lauterbrunnen's cliffs are
  snowy whether or not anyone is in the valley.
- **Seasonal snow line.** The Y level at which snow begins descends in autumn
  and winter and retreats in spring and summer. Four cardinal heights
  (one per season) interpolate smoothly across the year.
- **Permanent snow zone.** Above a configurable altitude, snow is always
  present regardless of season — these are the genuine high peaks.
- **Biome filtering.** Hot biomes (base temperature > 1.0) are skipped by
  default so the Sahara doesn't get alpine snow at altitude. Additional
  biomes can be blacklisted via config.
- **Tough As Nails integration.** When TAN is present, registers a
  positional temperature modifier that drops TAN's environmental
  temperature by one step per N blocks above a configurable baseline,
  letting cold damage scale with altitude.

## Optional dependencies

- **Serene Seasons** — read for the current season fraction. Without it,
  the mod falls back to a 96-day game-time cycle.
- **Tough As Nails** — for the altitude-temperature integration.

The mod loads fine without either dep but loses the corresponding feature.

## Config

After first launch, edit `config/alpineclimate-common.toml`. All snow lines,
the placement budget, biome filters, and TAN parameters are tunable.

Defaults are sized for a 1:1-scale Tellus world with `height_offset=64`
(sea level at y=64):

| Setting               | Default | Meaning                                            |
|-----------------------|---------|----------------------------------------------------|
| spring snow line      | 700     | mid-spring snowfall ceiling                        |
| summer snow line      | 1500    | effectively only the permanent zone is snowy       |
| autumn snow line      | 950     | mountain shoulders                                 |
| winter snow line      | 200     | deep into the valley floor                         |
| permanent snow        | 1100    | always snowy regardless of season                  |
| tick interval         | 5 s     | how often to run the global pass                   |
| max chunks per pass   | 64      | per-pass chunk budget                              |
| temperature baseline  | 200     | Y level treated as neutral by TAN modifier         |
| blocks per temp step  | 400     | altitude required to drop one TAN temperature step |

If your pack uses a different scale (e.g. `world_scale=30`), adjust the
snow lines downward proportionally.

## Build

```sh
./gradlew build
```

Output: `build/libs/alpineclimate-neoforge-1.21.1-<version>.jar`

## License

MIT.
