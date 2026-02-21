# SwiftShelf for Android TV

<p align="center">
  <img src="screenshots/banner.jpg" alt="SwiftShelf Icon" width="500"/>
</p>

A native Android TV client for [Audiobookshelf](https://www.audiobookshelf.org/), offering a sleek, remote-friendly interface for browsing and playing your audiobooks.

![Screenshot: Home](screenshots/home.png)

## Features

### Authenticated Connection & Secure Storage
- Logs in with Audiobookshelf host and API key
- Credentials securely stored using Android's EncryptedSharedPreferences
- Seamless reconnection after app relaunch

![Screenshot: Library Selection](screenshots/library-select.png)

### Library Selection & Persistence
- Choose from available Audiobookshelf libraries
- Select multiple libraries to browse at once
- Remembers your selected libraries

### Recent & Continue Listening Carousels
- Browse recently added audiobooks
- Pick up where you left off with in-progress items
- Cover artwork with author, duration, and playback progress
- Smooth horizontal scrolling carousels

<!-- ![Screenshot: Library Browse](screenshots/library-browse.png) -->

### Search
- Query your library with natural search
- Results display books with cover art and metadata
- Quick access to search functionality

![Screenshot: Search](screenshots/search.png)

### Detailed Item Popup & Quick Play
- Material Design dialog with book details
- Author, narrator, series information
- Duration and progress tracking
- Chapter selection for direct playback
- Play and Read buttons for audiobooks with ebooks

![Screenshot: Book Details](screenshots/book-details.png)

### Full-Screen Media Player
- Immersive playback experience with blurred cover background
- Transport controls: play/pause, skip forward/back (30s/10s)
- Playback speed adjustments (0.5x - 3.0x)
- Chapter navigation with expandable chapter list
- Progress scrubbing with time display
- D-pad optimized for Android TV remotes

![Screenshot: Media Player](screenshots/full-player.png)

### EPUB Reader
- Two-page landscape layout optimized for TV
- Sepia reading theme for comfortable viewing
- Chapter navigation menu
- Swipe or button navigation between pages

### Customizable Experience
- Adjust library item fetch count (default: 10)
- Choose carousel progress-bar color
- Set default playback speed
- Persistent settings across sessions

![Screenshot: Settings](screenshots/settings.png)

### Authenticated Cover Fetching
- Securely fetch cover images with authentication
- Cached for smooth browsing performance
- High-quality artwork display

### Android TV Optimized
- Remote-friendly navigation
- D-pad and touch support
- Focus-aware UI elements
- Material Design 3 components
- Landscape-optimized layouts

## Technical Features

- **Progress Sync**: Automatically syncs playback progress to Audiobookshelf (session sync every 15s, progress sync every 90s)
- **Session Management**: Proper playback session handling with Audiobookshelf server
- **MediaSession Integration**: System-level playback controls and notifications
- **ExoPlayer**: High-quality audio playback with chapter support
- **EPUB Support**: Built-in EPUB parser and reader for ebook content
- **Jetpack Compose**: Modern, declarative UI framework
- **Material Design 3**: Latest Android design guidelines

## Requirements

- Android TV device or Android device running Android 8.0 (API 26) or higher
- Audiobookshelf server instance with API access
- Network connection to your Audiobookshelf server

## Installation

### From Release
1. Download the latest APK from the [Releases](https://github.com/michaeldvinci/swiftshelf-android/releases) page
2. Install via ADB: `adb install swiftshelf-<version>.apk`
3. Or sideload using your preferred method

### Building from Source
1. Clone the repository
2. Open in Android Studio
3. Ensure you have JDK 17 installed
4. Build and run on your Android TV device or emulator

```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Configuration

### First Launch
1. Launch SwiftShelf on your Android TV
2. Enter your Audiobookshelf host URL — **include the full base path** if ABS is not served at the root:
   - Root install: `https://abs.example.com`
   - Subpath install: `https://myserver.com/audiobookshelf`
3. Choose your authentication method (API Key or Username / Password) and enter your credentials
4. Select one or more libraries to browse

### Debug Configuration (Development)
For testing during development, you can create a config file:

```json
// .swiftshelf-config.json
{
  "host": "https://your-abs-server.com",
  "apiKey": "your-api-key-here"
}
```

## Architecture

- **MVVM Pattern**: Clean separation of concerns
- **Repository Pattern**: Data layer abstraction
- **Kotlin Coroutines**: Asynchronous operations
- **StateFlow**: Reactive state management
- **Retrofit**: Network communication
- **Room** (planned): Local caching
- **Coil**: Image loading and caching

## Development

### Project Structure
```
app/src/main/java/com/swiftshelf/
├── MainActivity.kt              # Main entry point
├── SwiftShelfViewModel.kt       # App state management
├── audio/
│   ├── GlobalAudioManager.kt    # Audio playback management
│   └── MediaSessionManager.kt   # System media controls
├── data/
│   ├── model/                   # Data models
│   ├── network/                 # API client
│   └── repository/              # Data repositories
├── epub/
│   └── EPUBParser.kt            # EPUB file parsing
├── ui/
│   ├── screens/                 # Composable screens
│   │   ├── BookDetailsDialog.kt # Book info popup
│   │   ├── CompactPlayer.kt     # Mini player banner
│   │   ├── EpubReaderScreen.kt  # EPUB reader
│   │   ├── LibraryBrowseScreen.kt # Main browse UI
│   │   ├── MediaPlayerScreen.kt # Full-screen player
│   │   └── ...
│   └── theme/                   # App theming
└── util/
    └── SecurePreferences.kt     # Encrypted storage
```

### Building
- Minimum SDK: 26 (Android 8.0)
- Target SDK: 34 (Android 14)
- Kotlin: 1.9.20
- Gradle: 8.11.1
- Android Gradle Plugin: 8.10.1

## Roadmap

- [ ] Offline playback support
- [ ] Sleep timer
- [ ] Playback queue management
- [ ] Bookmarks
- [ ] Android Auto integration
- [ ] Cast support

## Debugging with ADB

Debug builds emit verbose HTTP logs (request lines, headers, and full response bodies) using the tags `SwiftShelf` and `SwiftShelf/HTTP`. Use `adb logcat` to capture these while reproducing an issue.

### Quick-start

```bash
# Clear the log buffer, then stream only SwiftShelf lines
adb logcat -c && adb logcat -s SwiftShelf:D SwiftShelf/HTTP:D

# If the device is connected over TCP/IP (common for Android TV)
adb connect <tv-ip>:5555
adb -s <tv-ip>:5555 logcat -c && adb -s <tv-ip>:5555 logcat -s SwiftShelf:D SwiftShelf/HTTP:D
```

### What to look for

| Log tag | What it shows |
|---|---|
| `RetrofitClient` | Base URL and token length when Retrofit is initialized |
| `SwiftShelf` | Auth flow steps: URL being used, token lengths, response codes |
| `SwiftShelf/HTTP` | Full HTTP request/response — URL, headers, and body |

### Diagnosing a 401

1. Run logcat before tapping **Connect**.
2. Look for the `RetrofitClient` line — verify `url=` ends with `/` and `tokenLen=` matches your key length.
3. Look for the `SwiftShelf/HTTP` `GET /api/libraries` request — confirm the `Authorization: Bearer` header is present and the URL path is correct.
4. Look for the response body of the 401 — the server usually includes an error message that explains what went wrong (e.g. `"Token not found"`, `"Invalid token"`, `"Unauthorized"`).

### Entering credentials via ADB

When using `adb shell input text` to type into the app, be aware that:

- **Use single outer quotes**: `adb shell 'input text "YOUR_VALUE"'` is the most reliable form. The outer single quotes prevent the local shell from interpreting special characters before they reach ADB; the inner double quotes preserve spaces within the value.
  ```bash
  adb shell 'input text "YOUR_API_KEY_HERE"'
  ```
- **Forms that truncate unexpectedly**: `adb shell "input text '$api_key'"` and `adb shell input text '$api_key'` both silently truncate long strings due to shell escaping / argument-splitting before ADB receives the text. Avoid them.
- **Newlines**: pressing Enter via ADB (`adb shell input keyevent 66`) submits the form — do this only after all fields are filled.
- **API key whitespace**: the app automatically trims leading/trailing whitespace from host URL, API key, and username fields, so accidental extra spaces from ADB input are stripped.

### Filtering noise

```bash
# Show only connection-related lines (hide verbose HTTP body lines)
adb logcat -s SwiftShelf:D

# Show everything including full HTTP body
adb logcat -s SwiftShelf:D SwiftShelf/HTTP:V
```

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

## License

[License information to be added]

## Acknowledgments

- [Audiobookshelf](https://www.audiobookshelf.org/) - The amazing audiobook server
- The Android and Jetpack Compose communities

## Support

For issues and feature requests, please use the [GitHub Issues](https://github.com/michaeldvinci/swiftshelf-android/issues) page.
