# HumanPhone

An Android app that puts a language model in charge of your phone: a chat client that can also
read the screen and tap, type, scroll and navigate on your behalf, exactly like a person using
the phone.

Two surfaces, one app:

- **Chat** — conversations with streaming replies, markdown rendering, image attachments, voice
  notes and a hands-free live mode that keeps listening after every spoken answer.
- **Operator** — an accessibility service plus a floating dot that hands a spoken or typed task
  to an agent loop, which reads the visible screen and drives real UI actions until the task is
  done. Every step is shown live in the app.

There is no HumanPhone account, no backend of ours and no telemetry. The app talks only to the
model endpoints you configure.

## Requirements

- Android 11 or newer (API 30+).
- An OpenAI-compatible chat endpoint: a local [Ollama](https://ollama.com) server, OpenRouter, or
  any custom base URL with `/chat/completions`.
- For the operator: the HumanPhone accessibility service enabled, and the "display over other
  apps" permission for the floating dot. Android hides both behind *Settings → Apps →
  HumanPhone → Restricted settings*.
- Optional: an OpenAI-compatible `/audio/transcriptions` endpoint (for example Whisper) to
  transcribe recorded voice notes. Without it the phone's own speech recogniser is used and voice
  notes are still recorded, just not transcribed.

## Chat

Conversations are stored on the device. A turn can carry JPEG images (encoded on device, long
edge capped) and a recorded voice note. Live mode loops speech recognition → model → speech
synthesis so you can hold a conversation hands-free; listening pauses while the model is talking
and while replies are being spoken. A bubble that shows the running state can be hidden from
settings.

## Operator

The agent loop exposes the following tools to the model:

| Tool | What it does |
| --- | --- |
| `read_screen` | Dump the visible screen: accessibility tree plus, optionally, a screenshot |
| `tap`, `tap_text` | Click a node by index, or the first node containing given text |
| `type_text`, `clear_text` | Type into the focused or indexed field, or clear it |
| `scroll`, `navigate` | Scroll a list; press back / home / recents / notifications |
| `open_app`, `list_apps`, `open_url` | Launch an app by name, list installed launchables, open a URL |
| `call`, `send_sms`, `find_contact` | Place a call, compose a text, look up a contact |
| `set_alarm`, `system_settings` | Create an alarm, open a system settings page |
| `web_search`, `fetch_page` | Search the web or fetch a page and return it as text |
| `submit`, `wait`, `wait_for_text` | Send the IME action, sleep, or wait for text to appear |
| `remember`, `recall` | Persist and read back small facts between runs |
| `speak`, `finish` | Say something out loud, or end the task with a summary |

Steps are bounded by *max steps* and delayed by *step delay* (both configurable), and a run can
be cancelled at any time. Screen contents are only sent to the model when *send screenshots* is
enabled; the accessibility tree text is what the agent normally works from.

## Settings

Provider kind, base URL, API key, model, temperature, max tokens, persona, spoken replies, speak
rate and pitch, screenshot sending, max steps, step delay, speech recognition and synthesis
language, prefer-offline recognition, the transcription endpoint, the floating dot and live mode.

## Build

JDK 17 and Android SDK platform 35 with build-tools 35.0.0 (`minSdk` 30, `targetSdk` 35).

```sh
./gradlew assembleDebug          # debug APK
./gradlew testDebugUnitTest      # unit tests
./gradlew assembleRelease        # release APK (unsigned)
```

`local.properties` must point at your SDK (`sdk.dir=/path/to/android-sdk`). CI (GitLab and
GitHub Actions) builds the debug APK and runs the unit tests on every push to `main`.

## Project layout

```
app/src/main/java/dev/humanagent/
  llm/      OpenAI-compatible client, streaming, provider config, settings store
  chat/     conversation store, chat engine (attachments, live mode)
  agent/    accessibility service, agent loop, tool definitions, UI actions, overlay dot
  voice/    speech recognition, text to speech, voice notes, transcription
  ui/       Compose screens (chat, operator, settings) and theme
  util/     markdown, HTML text, image encoding, JSON argument helpers
  diag/     crash log written to Downloads
```

## License

Copyright (C) 2026 HumanPhone contributors.

GPL-3.0-or-later — see [LICENSE](LICENSE). This program is free software: you can redistribute it
and/or modify it under the terms of the GNU General Public License as published by the Free
Software Foundation, either version 3 of the License, or (at your option) any later version.
