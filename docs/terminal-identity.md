# Terminal identity and shared sessions

herdr-core fixes a terminal's identity when the terminal starts. Programs pick their output features, such as image support, from that identity. Any client can attach later, so one session cannot suit both IntelliJ and an image-capable client such as the Herdr app. Checked with hc 0.1.17, pi 1.4.0 and IntelliJ IDEA 2026.2.

## How identity is set

- `hc run` passes the caller's `TERM_PROGRAM` to core as the identity. Core sets `TERM_PROGRAM` for the program and answers XTVERSION with it.
- The identity does not change for the life of the terminal, also when another client attaches.
- Without `TERM_PROGRAM`, core uses `ghostty`. IntelliJ sets no `TERM_PROGRAM`, so the plugin passes `TERM_PROGRAM=JetBrains-JediTerm` to `hc run` and the other `hc` commands of its sidebar actions.
- `hc attach` passes the program's output unchanged to every client.

## Effect on programs that show images

A program that checks the identity once at start sends the same output to every client for the life of the session.

| Session started by | Program output | In IntelliJ 2026.2 | In an image-capable client |
|---|---|---|---|
| The plugin (`JetBrains-JediTerm`) | text fallback | text | text, no images |
| A kitty-capable client (`ghostty`, `kitty`, `herdr`) | kitty graphics | empty space | images |

- The plugin cannot change the identity of terminals it did not start.
- Starting the program again in the same terminal does not help. To give a session the plugin's identity, exit the program, Finish the terminal, then Resume it. Resume of a finished terminal starts a new terminal through the plugin.

**Example with pi:** pi picks its image protocol from `TERM_PROGRAM` and similar variables when it starts, and does not query the terminal. A pi session started from the plugin shows text everywhere. A pi session started with the identity `ghostty` shows empty rows in IntelliJ where the images are.

## The `herdr-attach` helper

IntelliJ 2026.2 cannot draw kitty graphics and prints them as base64 text. Editor tabs therefore run `hc attach` through `herdr-attach`, a native helper bundled with the plugin. It runs `hc attach` in its own pty and removes kitty graphics commands (`ESC _G ... ESC \`) from the output.

- A separate process is necessary, because the IDE terminal API gives plugins no access to the output stream.
- The program does not know that its images are hidden. It keeps rows free for them, so the rows stay empty.
- The helper does not fix shared sessions. A session still shows images either nowhere or only outside IntelliJ.
- The build bundles the helper for the Rust targets in `helperTargets` in `gradle.properties`. On other platforms, editor tabs run `hc attach` directly and the base64 text shows.

## IntelliJ progress

- **2026.2:** the terminal engine is JediTerm. It supports no image protocol and prints unknown APC sequences as text.
- **2026.3:** the engine is libghostty. It drops unknown APC sequences, so the base64 text goes away without the helper. The IDE does not draw kitty graphics yet. See [The Terminal in JetBrains IDEs Now Runs on libghostty](https://blog.jetbrains.com/platform/2026/10/jetbrains-terminal-now-uses-ghostty/).
- **Plan:** remove the helper when the plugin requires 2026.3 or later. When IntelliJ draws kitty graphics, pass a kitty-capable identity, so sessions show images in both clients.

## Fixes needed upstream

**herdr-core**
- **Filter output per client.** Drop kitty graphics, or draw a placeholder, for clients that cannot draw them. This replaces the `herdr-attach` helper.
- **Neutral default identity.** Without `TERM_PROGRAM`, do not claim `ghostty`, because programs then send sequences that no client offered.
- **Answer the kitty graphics query.** Answer `ESC _G ... a=q` to match what core can forward.

**Programs**
- **Query, do not read `TERM_PROGRAM`.** Detect kitty graphics with the protocol query, so the answer comes from core instead of the starting client. This helps only after core answers the query. For pi, this is the needed fix.

A program that checks once at start still sends one output to all clients. Images in some clients and text in others need a program that changes its output while it runs, or a core that translates images for each client.
