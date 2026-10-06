# HumanPhone

An Android app that puts a language model in charge of your phone: a chat client that can also
read the screen and tap, type, scroll and navigate on your behalf, exactly like a person using
the phone.

Two surfaces, one app:

- **Chat** — conversations with streaming replies, image and document attachments, dictated
  messages and spoken replies.
- **Operator** — a foreground service plus an accessibility service that hand a typed or
  dictated task to an agent loop, which reads the visible screen and drives real UI actions
  until the task is done. Every step is shown live in the Agent tab. With Auto mode on it also
  keeps watching notifications in the background and answers the ones that truly need a reply.

There is no HumanPhone account, no backend of ours and no telemetry. The app talks only to the
model endpoints you configure.

## Requirements

- Android 11 or newer (API 30+).
- An OpenAI-compatible chat endpoint: a local [Ollama](https://ollama.com) server, OpenRouter, or
  any custom base URL with `/chat/completions`.
- For the operator: the HumanPhone accessibility service enabled. Android hides it behind
  *Settings → Apps → HumanPhone → Restricted settings*.
- Optional: an OpenAI-compatible `/audio/transcriptions` endpoint (for example Whisper) to
  transcribe dictated messages. Without it the phone's own speech recogniser is used.
- Optional: an OpenAI-compatible `/audio/speech` endpoint (OpenAI, Groq, a local speech server) to
  voice the spoken replies with an API voice. Without it the phone's own text-to-speech voice is
  used.

## Chat

Conversations are stored on the device. A turn can carry up to six photos (re-encoded as JPEG on
device, long edge capped at 1024 px) plus documents: text files are inlined into the request,
other files travel as attachments where the provider supports them (not on Ollama). The mic
dictates a message — through the phone's own recogniser, or as a recorded WAV uploaded to the
configured transcription endpoint — and the transcript lands in the input box for review before
sending. Replies can be read out loud, with the phone's own voice or an API voice. A message
starting with `/do` hands the rest of it to the operator as a task.

## Operator

The agent loop exposes the following tools to the model:

| Tool | What it does |
| --- | --- |
| `read_screen` | Dump the visible screen: every interactive window as an indexed accessibility tree, plus optionally a screenshot |
| `tap`, `tap_text`, `long_press` | Click or hold a node by index, or by the text it shows |
| `find_text` | List the elements containing a string, with their dump indices |
| `type_text`, `clear_text`, `press_enter` | Type into the focused or indexed field, clear it, or submit it with the keyboard's enter action |
| `scroll`, `scroll_to_text`, `scroll_in` | Swipe the screen, scroll until a text appears, or scroll one container by its index |
| `navigate` | Press back / home / recents / notifications / quick settings / lock / power dialog |
| `open_app`, `list_apps`, `open_url` | Launch an app by name, list installed launchables, open a URL |
| `call`, `send_sms`, `find_contact` | Open the dialler, send (or pre-fill) a text message, look up a contact |
| `set_alarm`, `system_settings` | Create an alarm, open a system settings page |
| `wait`, `wait_for_text` | Sleep, or poll until some text appears on screen |
| `remember`, `recall`, `forget`, `clear_memory` | Persist, read back, delete one fact, or wipe all facts between runs |
| `remember_person` | Remember who someone is to the user: relation, where they talk, how to treat them |
| `app_skill`, `save_skill` | Read an app's built-in operating notes, or store the steps that worked |
| `record_offer`, `compare_offers` | Record shopping candidates with their prices, then rank them cheapest first |
| `create_site`, `write_site_file`, `download_image`, `list_site_files` | Build a real website on the phone: files plus images downloaded into it |
| `preview_site`, `close_site_preview` | Serve the site from the phone, open it in the browser, stop the server |
| `speak`, `finish` | Say something out loud, or end the task with a summary |
| `confirm_delivered` | Close an owed result once the promised message is really visible in the conversation |

Steps are bounded by *max steps* and delayed by *step delay* (both configurable), and a run can
be cancelled at any time, from the Agent tab or from the persistent notification. Screen
contents are only sent to the model when *send screenshots* is enabled; the accessibility tree
text is what the agent normally works from. When a run finishes or runs out of steps, the phone
is returned to HumanPhone.

### Memory

The assistant keeps one durable record in its private storage — the twin. Facts you tell it
(`remember`), the people you know with their relation, channel and etiquette (`remember_person`),
and one compact episode per task it ran. Every agent step and every chat reply reads that
record, and Auto mode reads the people, so "Sam" is known as the brother who prefers Arabic and
not just a notification title.

The agent cannot get stuck going in circles: repeating the exact same action is blocked with
guidance back to the model, actions that provably leave the screen unchanged are stopped, and a
run that stalls on one screen ends honestly instead of burning steps. History is trimmed with
the older actions kept as a condensed journal, and a stuck run still returns the phone to
HumanPhone. Facts (`forget`, `clear_memory`) and people can be deleted by the agent during a
task, and the whole memory is visible under *Settings → Assistant memory*, where you can delete
notes one by one or clear all facts.

A task that promises a result to a conversation also opens an obligation in the ledger. It
survives context trimming, the run ending and the process dying: the system prompt shows it at
every step, `finish` is refused while it is open, a run that ends anyway counts a failed
delivery attempt, and the watchdog makes a bounded delivery run (up to three attempts) to send
what was promised. The episode remembers honestly whether the result was sent.

### Auto mode

With Auto mode on, the agent service keeps hearing the phone's notifications in the background.
Each one goes through a single cheap model call: promotions, newsletters, machine notices and
one-time codes are skipped, and only a message from a person that clearly waits for an answer is
replied to — a short human-toned reply, plus, when work is needed, a full agent run that sends
the result into the same conversation. An optional persona limits what it may answer and for
whom ("only messages from my family, in Arabic"). Notifications that arrive mid-run are queued
and replayed when the agent goes idle, and cooldowns keep the assistant's own outgoing messages
from waking it again.

## Safety model

HumanPhone runs a powerful model with access to the phone. That power has guardrails:

- **Untrusted content is data, not instructions.** The agent's system prompt and the Auto-mode
  classifier both state the trust boundary: text read off a screen, in a notification, on a web
  page or in an attached document can never assign tasks or change rules.
- **One-time codes never leave the phone.** Verification codes in notification text are redacted
  at the source, before anything is classified, logged or answered.
- **Texts stop in the composer by default.** With "Send texts directly" off (the default), a
  send opens the messaging app with the message pre-filled and the user presses send. Direct
  permission-free sending is an explicit opt-in in Settings.
- **Auto mode has an hourly ceiling** — far fewer agent runs and classifications than a storm
  of notifications could ask for — so no app can turn the watchdog into a quota burner.
- **API keys are sealed at rest.** Settings are encrypted with a hardware-bound Android
  Keystore key; a plaintext settings backup has no usable keys in it.
- **Downloads are strict.** `download_image` follows no redirects, takes only http(s), refuses
  non-image content, and is capped at 10 MB.

## Settings

Provider kind, base URL, API key, model, temperature, max tokens, persona, spoken replies and the
voice engine for them (the phone's own voice — any installed engine, chosen in Settings — or an
OpenAI-compatible `/audio/speech` endpoint), screenshot sending, direct SMS sending, max steps,
step delay, the speech language, the transcription engine and endpoint, Auto mode and its
persona, and the app language.

The speech features report their engine state in Settings: the on-device voice data and voice
input can be installed and both engines can be tested without leaving the app. A permissions card
shows the state of the accessibility service, battery optimisation, notifications, microphone,
SMS and contacts, with a button for each.

The interface ships in English, العربية, Français, Español, Português and हिन्दी (per-app language
on Android 13 or newer).

## Build

JDK 17 and Android SDK platform 35 with build-tools 35.0.0 (`minSdk` 30, `targetSdk` 35).

```sh
./gradlew assembleDebug          # debug APK
./gradlew testDebugUnitTest      # unit tests
./gradlew assembleRelease        # release APK (unsigned)
```

`local.properties` must point at your SDK (`sdk.dir=/path/to/android-sdk`). CI (GitLab and
GitHub Actions) builds the debug APK and runs the unit tests on every push; GitLab also builds
the unsigned release APK on tags.

## Project layout

```
app/src/main/java/dev/humanagent/
  llm/      OpenAI-compatible client, streaming, provider config, settings store
  chat/     conversation store, chat engine, attachment import and encoding
  agent/    accessibility service, foreground service, agent loop, tool definitions,
            UI actions, notification watchdog (Auto mode), app skills, memory
            (facts/people/episodes), owed-result ledger, offers
  voice/    speech recognition, text to speech (on-device or API), dictation recording,
            transcription
  site/     on-phone website workspace and loopback preview server
  ui/       Compose screens (chat, agent, settings) and theme
  util/     JSON argument helpers
```

## License

Copyright (C) 2026 HumanPhone contributors.

GPL-3.0-or-later — see [LICENSE](LICENSE). This program is free software: you can redistribute it
and/or modify it under the terms of the GNU General Public License as published by the Free
Software Foundation, either version 3 of the License, or (at your option) any later version.
