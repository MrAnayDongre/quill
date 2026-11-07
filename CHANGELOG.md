# Quill Changelog

## [Unreleased]

### Added

- Ghost text completion through the platform inline completion API, streamed as the model writes
- OpenAI compatible backend for completions (fill in the middle) and chat completions endpoints
- Early stop at a stop sequence, a closing fence or the end of the line for single line suggestions
- Debounce that shortens after structural characters, stale request cancellation and a cache of recent completions
- Connection warm-up that sends no credentials
- Status bar widget with the median and 95th percentile time to the first suggestion character
- Settings page with presets, a connection test, and the API key kept in the IDE password safe
- File exclusion patterns, with `.env` files always excluded
- One notification per failure streak, and a separate not set up state when a hosted server has no key
- Latency budget that shortens suggestions when the server is slow
