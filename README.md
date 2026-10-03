# StringDuper

Paper 26.2 plugin that restores string duplication when water flows from a waterlogged trapdoor into attached tripwire. The listener validates both block states, cancels the flow only when duplication is allowed, and drops one string. It uses Paper events and does not depend on pistons or NMS.

## Configuration

```yaml
enabled: true

modules:
  max-active: 4

limits:
  per-tick: 0.46
  per-second: 20.0
  per-hour: 5000.0
```

The three rates are converted to strings per second using 20 ticks per second. The lowest equivalent rate is used by one global token bucket. It refills using elapsed real time, is also capped by the per-tick rate, and has finite capacity to limit bursts. For the default values, `per-hour` is the tightest rate.

An active module is tracked by the world and position of its waterlogged trapdoor source. Entries expire after 60 seconds without a qualifying flow. The configured module count applies globally.

## Command

Operators can use `/stringduper on`, `/stringduper off`, and `/stringduper toggle`. The startup state comes from `enabled`.
