# Quill Changelog

## [Unreleased]

### Added

- Ghost text completion through the platform inline completion API
- OpenAI compatible backend with fill in the middle prompts, for completions and chat completions endpoints
- Debounce, stale request cancellation, request timeout and a cache of recent completions
- Latency budget that shortens suggestions when the server is slow
- Single line completions when text follows the caret
- Settings page with the API key kept in the IDE password safe
- File exclusion patterns, with `.env` files always excluded
- One notification per failure streak
