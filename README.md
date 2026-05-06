# Pornhub Extension for Aniyomi

<p align="center">
  <img src="res/mipmap-xxxhdpi/ic_launcher.png" alt="Pornhub Logo" width="120">
</p>

<p align="center">
  <strong>Multi-language adult video extension for Aniyomi</strong>
</p>

---

## 📖 About

This is an **Aniyomi extension** that provides access to [Pornhub](https://www.pornhub.com). Browse, search and stream content directly within the Aniyomi app.

⚠️ **NSFW** — Adults only.

### Features

- ✅ **Browse & Search** — discover thousands of titles
- ✅ **Filters** — by category, production, etc.
- ✅ **Direct streaming** — multiple qualities resolved server-side
- ✅ **Latest Updates** — newest content sorted by date

---

## 🚀 Installation

### Prerequisites

- **[Aniyomi](https://github.com/aniyomiorg/aniyomi)** app installed on your Android device
- Android 6.0 (API 23) or higher

### Installation Steps

1. Download the latest `.apk` file from the [Releases](../../releases) page
2. Open **Aniyomi** app
3. Go to **Browse** → **Extensions**
4. Tap the **Install** button (📦 icon) in the top right
5. Select the downloaded `.apk` file
6. Grant necessary permissions if prompted
7. The extension will appear in your **Sources** list

---

## 🛠️ Technical Details

### Built With

- **Language**: Kotlin
- **Min SDK**: Android 6.0 (API 23)
- **Framework**: Aniyomi Extension API
- **Architecture**: ParsedAnimeHttpSource

### Extraction Flow

1. HTML scraping of `pornhub.com` for browse / search / details
2. JS-encoded `mediaDefinitions` parsing for video sources
3. Custom `PhCdnExtractor` for the actual stream URLs (multiple qualities)

---

## 🐛 Troubleshooting

### No videos appear
- Try a different region/VPN — some content is geo-locked
- The site occasionally rotates its anti-bot challenge; refresh the source

### Extension not showing in sources
- Make sure you installed the correct `.apk` file
- Restart the Aniyomi app

---

## 📝 License

This extension is provided as-is for personal use.

## ⚠️ Disclaimer

Not affiliated with Pornhub or Aniyomi. Educational purposes only. You must comply with your local laws regarding adult content.
