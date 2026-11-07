# Quill

Ghost text completion for JetBrains IDEs that streams suggestions as the model writes them, and shows you how fast it is.

Median time from request to the first suggestion character: **250 ms**, against 392 ms until the whole suggestion has arrived. That is 20 runs of a 418 character multi-line completion with `deepseek-v4.1-flash-fast` on CoralBricks, on 2026-11-07 from a Linux desktop. The debounce is not included: it is 20 ms after `.`, `(`, `,`, `{`, `=`, `:` and a newline, and 150 ms by default in the middle of a word. On a local Ollama `llama3.1` (6 runs, 44 characters on average) the median was 94 ms against 230 ms; the 95th percentile was 6.8 s because of a cold model load.

Quill talks to any OpenAI compatible server, so you pick the model and keep the key. Tab accepts a suggestion. The platform's actions for accepting the next word or line are in Settings | Keymap, under "inline completion".

## Run it

```
./gradlew runIde
```

This starts a sandbox IntelliJ IDEA with Quill installed. Open Settings | Tools | Quill, pick a preset, paste a key if the server needs one, and press **Test Connection**. It sends one real request and shows, for example, `Connected · first token 290 ms · "a + b"`. Then type in any source file.

The status bar shows the median time to the first suggestion character (`Quill 250 ms`), with the 95th percentile in the tooltip. It reads `Quill: add key` when a hosted server has no key yet, and `Quill: offline` after a failure. Click it to open the settings.

## Why it feels fast

- **Streaming ghost text.** Characters appear while the model is still writing. Output is cleaned as it arrives, holding back only the few characters that could still turn into a code fence, a stop sequence or a repeat of the text after the caret. A test feeds a dozen messy answers in every chunk size and checks that the streamed result equals the cleaned whole.
- **Stops reading early.** When text follows the caret on the same line, the suggestion is a single line, and the request is cancelled at the first newline. The same happens at a stop sequence or a closing fence. That saves latency and tokens.
- **Adaptive debounce.** After `.`, `(`, `,`, `{`, `=`, `:` and a newline the request goes out almost at once. In the middle of a word it waits the configured delay.
- **Warm connection.** A credential free request opens the connection when you start working and again after a pause. In a small test (6 fresh clients each, noisy network) the first request's time to first character had a median of about 660 ms warmed against about 900 ms cold, and the best case was 248 ms against 418 ms.
- **One request at a time.** A new request cancels the one in flight, all the way down to the HTTP stream. Identical prompts are answered from a small cache.
- **Latency budget.** If the server's average gets slower than 1.5 seconds, the token allowance shrinks in proportion. With fast servers it never triggers.

## Why the suggestions are accurate

Quill gives the model what a person reading the code would look up, using the IDE's own index instead of guessing from text:

- **Enclosing scopes.** The class and function headers around the caret, even when they are far above the prompt window.
- **Receiver members.** After `receiver.` it lists the members of the receiver's type, the way a completion popup would. The type is found by following the references in the receiver's declaration, so it works for variables, parameters, fields, calls and classes named directly.
- **Declarations in use.** The signatures of the functions, classes and fields referenced in the last few lines, from anywhere in the project.
- **Neighbour files.** The first lines of other open or recently edited files.

This uses the reference and name APIs that every language plugin implements, so there is no per language code. It was tested on Java; Kotlin and other languages should work the same way but are not covered by a test yet.

Measured on a small made-up Java project of 8 files, with 11 cross-file completions such as `pricing.applyDiscount(order`, `base.times(` and `order.getCustomer().getLoyaltyTier()`: **11 of 11** began with the right call with project context against **5 of 11** with the same prompt window and no context (`deepseek-v4.1-flash-fast`, 2026-11-07). The score only checks that the suggestion starts with the expected call, and 11 cases is a smoke test, not a benchmark. Median time to the whole suggestion was 324 ms with context against 246 ms without, so the extra context costs roughly 80 ms. Comparisons with other tools have not been run.

## How a suggestion is made

1. The text before and after the caret is trimmed to a token budget at line boundaries, with the context above added as comments.
2. A `.../v1/completions` endpoint gets `prompt` and `suffix` (the model must support fill in the middle). A `.../v1/chat/completions` endpoint gets the code around a cursor marker as a chat message, which works with chat only models.
3. The answer streams back, is cleaned, and is shown.

## Settings

| Setting | Meaning |
| --- | --- |
| Preset | Fills in endpoint and model for Ollama or CoralBricks |
| Endpoint | OpenAI compatible `/v1/completions` or `/v1/chat/completions` URL |
| Model | Model name sent with each request |
| API key | Kept in the IDE password safe, never in settings files or logs |
| Max tokens | Upper bound for one suggestion |
| Debounce | Pause after the last keystroke, mid word |
| Excluded files | Comma separated globs matched against file names |

## Privacy and safety

- Files matching the excluded patterns, and always `.env` files, are never the edited file or context.
- Only the text around the caret, plus a few lines from up to three other project files, leaves the IDE.
- The key is sent only over https or to a local server. Redirects are not followed. The warm-up request carries no key.
- A failing server produces one notification, then silence until a request succeeds again. Notifications and logs carry a status code, never request or response bodies.

## Limits

- The inline completion API of the platform is marked internal by JetBrains and may change between IDE versions. The plugin targets IntelliJ IDEA 2026.2 (build 262).
- Token budgets use four characters per token. Context comes from the head of other files, not from symbol resolution.
- A hosted server with no key stored is treated as not set up; an open remote server needs any placeholder key.

## Development

```
./gradlew check verifyPlugin buildPlugin
```
