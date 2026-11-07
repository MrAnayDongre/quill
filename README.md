# Quill

Inline code completion for JetBrains IDEs. Quill shows suggestions as ghost text while you type and talks to any server that implements the OpenAI completions API: llama.cpp, Ollama and vLLM. Two request styles are supported, chosen by the endpoint URL: `.../v1/completions` uses the `suffix` field for fill in the middle (the model must support it), and `.../v1/chat/completions` sends the code around a cursor marker as a chat message, which works with chat only models and hosted providers such as CoralBricks (`https://inference.coralbricks.ai/v1/chat/completions`).

Press `Tab` to accept the whole suggestion. The platform also ships actions to accept the next word or the next line; bind them under Settings | Keymap by searching for "inline completion".

## Run it

```
./gradlew runIde
```

This starts a sandbox IntelliJ IDEA with Quill installed. Start a model server first, for example `ollama serve` with `ollama pull qwen2.5-coder:1.5b-base`, then open Settings | Tools | Quill and check the endpoint. Open any source file and start typing.

## Settings

| Setting | Meaning |
| --- | --- |
| Endpoint | Full URL of a completions or chat completions endpoint, for example `http://localhost:11434/v1/completions` |
| Model | Model name sent with each request |
| API key | Stored in the IDE password safe, never in settings files or logs |
| Max tokens | Upper bound for one suggestion |
| Debounce | Pause after the last keystroke before a request is sent |
| Excluded files | Comma separated globs matched against file names |

## How a suggestion is made

1. The text before and after the caret is sent as `prompt` and `suffix`, trimmed to a token budget at line boundaries. Servers that ignore `suffix` still work, with prefix only completion.
2. A few lines from the head of other open or recently edited project files are added as comments above the prefix.
3. The answer is cleaned: code fences are removed, the text is cut at language specific stop sequences, and anything that repeats the text after the caret is dropped.
4. Identical prompts are answered from a small cache. A new request cancels the one still in flight.

## Two choices that matter in daily use

Other ideas considered: accept by word or line (the platform already provides it), next edit prediction (needs an edit history and a second model contract), symbol aware retrieval across files (heavier than the head of file context used here), speculative prefetch on idle (doubles server load for a small gain).

Implemented:

- **Latency budget.** Quill tracks a moving average of response times. When the server is slower than 1.5 seconds, the token allowance shrinks in proportion, so suggestions stay short and keep arriving instead of landing after the user has moved on. It recovers by itself when the server speeds up.
- **Shape aware completion.** When text follows the caret on the same line, Quill asks for a single line, with a lower token limit. Mid line completions never spill into unrelated lines, and they return faster.

## Privacy and safety

- Files matching the excluded patterns, and always `.env` files, are never used as the edited file or as context.
- Only the text around the caret, plus a few lines from up to three other project files, leaves the IDE.
- The API key is sent only over https or to a local server. Redirects are not followed.
- A failing server produces one notification, then silence until a request succeeds again. Notifications and logs carry a status code, never request or response bodies.

## Limits

- The inline completion API of the platform is marked internal by JetBrains and may change between IDE versions. The plugin targets IntelliJ IDEA 2026.2 (build 262).
- Token budgets use four characters per token.
- Context comes from the head of other files, not from symbol resolution.

## Development

```
./gradlew check verifyPlugin buildPlugin
```
