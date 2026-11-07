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
- **Recent edits.** The last few lines you changed, in any file, because what someone just did is the best hint of what they do next. Text from excluded files is never kept.
- **Neighbour files.** The first lines of other open or recently edited files.

This uses the reference and name APIs that every language plugin implements, so there is no per language code. Tested on Java and Kotlin (the context and the check both work) and on TypeScript (the context works; the check never blocks a suggestion there, because TypeScript does not report an unknown property as an unresolved reference). Python is not covered: the IDE used for testing has no Python support.

## Measured

One synthetic Java project of 11 small classes, 20 completions that need knowledge from other files (`pricing.applyDiscount(order`, `shipping.quoteShipping(order`, `order.getCustomer().getLoyaltyTier()`, and so on), `deepseek-v4.1-flash-fast`, 2026-11-07. "Correct" means the suggestion starts with the expected call. "Invented" means it uses a name the project does not define. Two full runs; where they differ both numbers are given. Run to run noise is about 2 of 20.

| | Correct | Invented name | Median | 95th percentile |
| --- | --- | --- | --- | --- |
| Prompt window only | 6 to 7 of 20 | 13 of 20 | 230 to 290 ms | 0.6 s |
| With project context | 19 of 20 | 7 of 20 | 245 to 250 ms | 0.5 to 0.8 s |
| With project context and neighbour files | 20 of 20 | 5 to 7 of 20 | 270 to 360 ms | 0.7 s |
| With project context and verification | 19 of 20 | 0 of 20 | 395 to 405 ms | 1.1 to 1.3 s |

What each part is worth, as far as these numbers say:

- **Project context** is the big one: about 6 of 20 to 19 of 20 correct.
- **Neighbour files** added one correct suggestion and removed up to two invented ones in both runs. That is within the noise, and the neighbour files here are tiny, so their first lines are the API itself. No clear value shown.
- **Verification** removes the invented names completely (7 of 20 to 0 of 20) at the cost of about 150 ms on the median and a 95th percentile near 1.2 s. When a line cannot be repaired, Quill shows the sound start of the line (`quoteShipping(order, ` and not the invented argument), which is what keeps 19 of 20 correct. Without that rule it withheld 4 to 6 of the 20 suggestions.
- **Name repair on the spot** fired twice per run when the prompt had no project context (7 to 8 correct became 9) and never when it had, because with the context the model rarely invents a name that has one clear match.
- **Recent edits** were tried on 4 cases built so that the answer exists only in a line edited in another file (a `TODO(owner)` convention, an error message prefix, a constant name, a log prefix): 0 of 4 without, 4 of 4 with, in both runs. That shows the mechanism works when the hint is there. It says nothing about how often real edits help.
- An extra list of "real names that may fit" in the retry hint did not help and was removed.

Comparisons with other tools have not been run.

## Verified ghost text

Models invent names: a method that sounds right but does not exist. Quill checks every line against the IDE's own index before you see it.

1. Each completed line is parsed in place, in a copy of your file, and every reference it adds must resolve.
2. A line that uses a name which does not exist is held back. If one real name of the project clearly matches (`getTier` for `getLoyaltyTier`, `addLineItem` for `addLine`: all words of the shorter name appear in the longer, and only one candidate qualifies), the name is replaced on the spot and checked again, with no extra request. Otherwise the model is asked once more, with a comment above the line saying which names do not exist. Lines that already passed stay on screen.
3. If the second try still fails, Quill shows the part of the line that is still sound, and nothing more.

Checking costs about 25 ms per line on a 2,000-line Java file (measured warm; the first lines of a session take 60 to 300 ms), and files over 100,000 characters are not checked. Verified lines appear a line at a time instead of character by character; turn verification off in the settings to get character streaming back. The status bar tooltip counts the names kept off the screen.

The check is by name, so it catches invented members and functions, not wrong argument types. It is skipped for a file when the lines just before the caret already contain names the IDE cannot resolve (no SDK configured, a dynamic language, code you are in the middle of writing), because one unknown type makes everything used on it unknown too.

## Tried in a real IDE window

The sandbox IDE was run on a private X display and driven with real key presses and screenshots, on a small Java project opened as a project, with the model reached through a local relay that adds the key. What was seen:

- Typing `pricing.a` produced grey ghost text completing to `pricing.applyDiscount(order, null);` with the platform's "Tab to complete" hint, and Tab inserted it into the file.
- The request that was sent contained the enclosing scopes, `members of PricingService` and the declarations in use. A first answer that used an unknown name triggered a second request with the "do not use these names" comment above the line.
- The status bar showed the latency (`Quill 1,154 ms` through the relay, whose extra hop and new connection per request inflate it), and clicking it opened the settings page. Test Connection reported `Connected · first token 495 ms`.
- Seen and fixed: the IDE's own rewriting of a `.gitignore` was recorded as a recent edit. Only edits made in an editor are kept now.
- Not seen: any language other than Java in the window, and use on a large real project.

## What informed the design, and what was tried

- **Recent edits as context** follows the edit prediction work in [Zed's Zeta](https://zed.dev/blog/edit-prediction) and [Sweep's next-edit model](https://blog.sweep.dev/posts/oss-next-edit), which feed the model what you just changed. Quill uses the idea for plain completion; it does not predict edits elsewhere in the file. Its measured effect is under Measured above.
- **Checking against the code** follows the argument for deterministic repair of invented names over asking the model again ([FORGE 2026](https://arxiv.org/html/2601.19106v1), whose results come from its authors' own tool and a 200 snippet set they built). That is why a clear name match is fixed locally before the model is asked again.
- **Structural context from the IDE's index** is my own design, not taken from a paper; the measurement above is far too small to compare it with retrieval based approaches.
- **Tried and dropped: prompt cache layout.** Putting volatile context after the cursor raised the provider's cached share of the prompt from about 0.1 to 0.86 in a typing simulation, but a warmed-up control reached the same share and the median latency did not change (about 450 ms in every layout), so the layout was left alone.

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
| Check suggestions | Hold back lines with names that do not exist and ask again |
| Max tokens | Upper bound for one suggestion |
| Debounce | Pause after the last keystroke, mid word |
| Excluded files | Comma separated globs matched against file names |

## Privacy and safety

- Files matching the excluded patterns, and always `.env` files, are never the edited file or context.
- Only the text around the caret, plus a few lines from up to three other project files, the declarations and members described above, and your last few edited lines in files of this project, leave the IDE. Edits made in another window, or in files outside the project, are never sent.
- The key is sent only over https or to a local server. Redirects are not followed. The warm-up request carries no key.
- A failing server produces one notification, then silence until a request succeeds again. Notifications and logs carry a status code, never request or response bodies.

## Limits

- The inline completion API of the platform is marked internal by JetBrains and may change between IDE versions. The plugin targets IntelliJ IDEA 2026.2 (build 262).
- Token budgets use four characters per token. Context comes from the head of other files, not from symbol resolution.
- A hosted server with no key stored is treated as not set up; an open remote server needs any placeholder key.

## Development

```
src/main/kotlin/dev/anay/quill
  backend/     OpenAI compatible streaming client, endpoint rules, connection probe
  completion/  engine, prompt building, output cleanup, verification loop, name repair, stats
  context/     symbols, recent edits, neighbour files, the project based verifier
  settings/    persistent settings, password safe key store, settings page
  ide/         inline completion provider, application service, status bar
```

All text the user reads is in `messages/QuillBundle.properties`. Classes outside `ide` and `settings` are `internal`.

```
./gradlew check verifyPlugin buildPlugin
```
