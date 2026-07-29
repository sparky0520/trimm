# Cloud roadmap

GitHub Issues in `sparky0520/ctrlb-cloud` are the canonical cloud ticket system.

Current themes:

- [Persistent multimodal agent runs and activity events](https://github.com/sparky0520/ctrlb-cloud/issues/1)
- [Authorized full-video/proxy asset ingestion and deletion](https://github.com/sparky0520/ctrlb-cloud/issues/2)
- [Durable derivative, usage, cache, and activity storage](https://github.com/sparky0520/ctrlb-cloud/issues/3)
- [Generated versioned edit-program contracts](https://github.com/sparky0520/ctrlb-cloud/issues/4)
- [Production billing enforcement](https://github.com/sparky0520/ctrlb-cloud/issues/5)
- [Production rights-cleared audio catalog](https://github.com/sparky0520/ctrlb-cloud/issues/6)
- [Contextual adaptive speech editing instead of fixed filler and pause rules](https://github.com/sparky0520/ctrlb-cloud/issues/7)
- [Malformed AI proposal repair without losing the agent run](https://github.com/sparky0520/ctrlb-cloud/issues/8)

Historical monorepo ticket numbers are reference-only. New work is tracked in the repository that owns its implementation.

Recommended implementation order: #4, #3, #2, #1, #8, then #7. Billing (#5)
and catalog operations (#6) can proceed independently. The ordering is a
dependency guide, not a requirement to finish an entire issue before landing a
tested vertical slice.
