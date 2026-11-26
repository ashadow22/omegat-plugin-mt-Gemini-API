# Google Gemini API Plugin for OmegaT 6.1.1+

A lightweight Machine Translation plugin for **OmegaT 6.1.1+** powered by the **Google Gemini API**.

## Features

* **Dynamic Model Selection:** Defaults to `gemini-3.5-flash-lite`, with options to refresh or specify custom models.
* **Custom System Prompt:** Tailor translation style, tone, and domain instructions.
* **Glossary & Context Integration:** Injects project glossary terms and surrounding segments (preceding/following) for consistency.
* **Tag Preservation:** Maintains OmegaT inline tags (`<g0>`, `</g0>`, `<x1/>`) in their exact positions.
* **Reliability:** Built-in connection testing, request counters, and automatic retry logic for rate limits (`429` / `503`).

## Installation

1. Download the `.jar` file from the [Releases](../../releases) page.
2. Place it in your OmegaT user `plugins` directory:
   * **Linux / macOS:** `~/.omegat/plugins`
   * **Windows:** `%APPDATA%\OmegaT\plugins`
3. Restart OmegaT.

## Configuration

1. Go to **Options → Machine Translation** in OmegaT and enable **Google Gemini API**.
2. Click **Configure**, enter your Google AI Studio API key, adjust settings if needed, and click **OK**.
3. *Tip:* Enable "Fetch translations only for untranslated segments" to save API usage.

## Building from Source

Requires **Java 11+**:
```bash
./gradlew clean jar
```
The output will be located in `build/libs/`.

## License

Licensed under the **GNU General Public License v3.0 (GPL-3.0)**.
