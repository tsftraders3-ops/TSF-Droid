<h1 align="center">TSF Droid</h1>

<p align="center">
  <strong>🤖 An Open Autonomous AI Agent for Android</strong>
</p>

<p align="center">
  <em>Your phone. Your rules. Your AI.</em>
</p>

---

## 🎯 What is TSF Droid?

TSF Droid is a **fully autonomous AI agent** that lives on your Android phone and actually *does things* for you — planning multi-step goals, executing them on-device, verifying results, and adapting when anything fails.

> *"Check if it's going to rain tomorrow, and if so, text my wife that I'll be late and set an alarm for 6 PM."*

TSF Droid will **plan** this as 3 steps, **execute** each one, **verify** the results, and **adapt** if anything fails — all without you lifting a finger.

## ✨ Highlights

- **Autonomous Agent Engine** — self-planning DAG plans, re-evaluation after every step, habit & routine detection
- **Keyless OpenCode Zen provider** — free-tier LLM access with no API key, dynamic model discovery, graceful fallback chain alongside 12 BYOK providers (Gemini, Claude, OpenAI, Groq, Ollama, …)
- **Full device control** — 129 agent actions across system, communication, productivity, navigation, media, and smart home
- **UI Idle Settle Barrier** — accessibility gestures wait for a stable view hierarchy before dispatching (350 ms stability window, 2 s hard timeout)
- **Confirmation Gate** — SMS, calls, UPI payments, and system modifications are flagged `critical` and always require explicit user confirmation
- **Blind-spot handling** — `FLAG_SECURE` black frames and Flutter/Unity empty node trees abort gracefully into intent fallbacks instead of blind clicking
- **On-device inference** — LiteRT-LM (Gemma, Qwen) with background model downloads, SHA-256 verification, and GPU/NPU acceleration
- **4-tier Personal Knowledge Graph** — temporary, long-term, learned patterns (confidence-scored), and hardware-encrypted sensitive memory

## 🏗️ Build

```bash
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

CI builds run automatically via GitHub Actions (`.github/workflows/build-and-test.yml`, JDK 21). Unit tests gate every push; the debug APK is uploaded as a build artifact.

Requires **JDK 21** (pinned via `gradle/gradle-daemon-jvm.properties`) and **Android SDK 36**.

## 🔐 Permissions

First launch guides you through granting: Accessibility Service (UI automation), Write Settings (system toggles), Microphone (wake word), and Notification Access (smart auto-reply).

## 📜 License & Attribution

Apache License 2.0. TSF Droid is a fork of [**OpenDroid**](https://github.com/yashab-cyber/opendroid) by Yashab Alam and contributors — all credit for the original architecture goes to them. See [LICENSE](LICENSE).
