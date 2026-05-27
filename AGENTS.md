# AGENTS.md — EchoLingo Android App

This file gives AI assistants full context about the EchoLingo Android project.
Read this before making any changes.

---

## Project Overview

**EchoLingo** (also called EchoLingo) is a language-learning app that:
1. Takes a YouTube video URL as input
2. Fetches dual subtitles (source language + translation) via a backend server
3. Plays the video with subtitle overlay and an interactive transcript sidebar

This repo contains **two sub-projects**:

| Sub-project | Location | Language | Purpose |
|---|---|---|---|
| Android App | `android/` | Kotlin | Native Android UI |
| Spring Boot Server | `server/` | Java 21 | Backend API (yt-dlp, translation, cache) |
| (Legacy Node.js) | `backend/` | JavaScript | Original backend — kept for reference |

> **The Android app cannot work standalone.** `yt-dlp` is a Python binary — it must run on the server. The Android app calls the Spring Boot API for all video processing.

---

## Architecture

```
Android App (Kotlin + Compose)
      │
      │  HTTP (Retrofit2 + OkHttp)
      ▼
Spring Boot Server (Java 21)
      │
      ├── ProcessBuilder("yt-dlp") ──→ YouTube CDN
      ├── OkHttp ──────────────────→ DeepL / Google Translate
      ├── OkHttp multipart ─────────→ Groq Whisper API
      └── File cache ───────────────→ server/cache/{videoId}/
```

---

## Android App

### Location
`android/` — standard Android Gradle project (Kotlin DSL, `build.gradle.kts`)

### Language & SDK
- **Kotlin** (NOT Java — Google has deprecated Java for new Android development)
- Min SDK: **26** (Android 8.0)
- Target SDK: **35**
- Kotlin: **2.x** (latest stable)

### Architecture Pattern
**Clean Architecture + MVVM**

```
ui/         ← Compose screens + ViewModels
domain/     ← Use cases + domain models (pure Kotlin, no Android deps)
data/       ← Repositories, API client, Room DB
di/         ← Hilt modules
```

### Key Libraries (exact versions in `android/app/build.gradle.kts`)

```kotlin
// UI
implementation("androidx.compose.ui:ui")
implementation("androidx.compose.material3:material3")
implementation("androidx.navigation:navigation-compose:2.7.x")

// Video
implementation("androidx.media3:media3-exoplayer:1.x.x")
implementation("androidx.media3:media3-ui:1.x.x")

// Networking
implementation("com.squareup.retrofit2:retrofit:2.x.x")
implementation("com.squareup.retrofit2:converter-gson:2.x.x")
implementation("com.squareup.okhttp3:logging-interceptor:4.x.x")

// DI
implementation("com.google.dagger:hilt-android:2.x.x")
kapt("com.google.dagger:hilt-android-compiler:2.x.x")

// Storage
implementation("androidx.room:room-runtime:2.x.x")
kapt("androidx.room:room-compiler:2.x.x")
implementation("androidx.datastore:datastore-preferences:1.x.x")

// Async
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.x.x")
```

### Screens

| Screen | File | Route |
|---|---|---|
| Home | `ui/home/HomeScreen.kt` | `/` |
| Player | `ui/player/PlayerScreen.kt` | `/player/{videoId}` |
| History | `ui/history/HistoryScreen.kt` | `/history` |
| Settings | `ui/settings/SettingsSheet.kt` | Bottom sheet |

### Domain Models

```kotlin
// android/domain/model/Cue.kt
data class Cue(
    val index: Int,
    val startMs: Long,   // milliseconds
    val endMs: Long,     // milliseconds
    val text: String,
)

// android/domain/model/VideoMeta.kt
data class VideoMeta(
    val videoId: String,
    val title: String,
    val duration: Int,
    val thumbnailUrl: String,
    val hasDe: Boolean,
    val hasEn: Boolean,
    val scores: SubtitleScores,
)

// android/domain/model/SubtitleScores.kt
data class SubtitleScores(
    val syncScore: Int,
    val qualityScore: Int,
    val translationScore: Int,
    val overallScore: Int,
)
```

### API Client (Retrofit)

```kotlin
// android/data/api/EchoLingoApi.kt
interface EchoLingoApi {
    @POST("api/import")
    suspend fun importVideo(@Body req: ImportRequest): ImportResponse

    @GET("api/subtitles/{videoId}/{lang}")
    suspend fun getSubtitles(
        @Path("videoId") videoId: String,
        @Path("lang") lang: String,
    ): List<CueDto>

    @GET("api/meta/{videoId}")
    suspend fun getMeta(@Path("videoId") videoId: String): VideoMetaDto

    @GET("api/video/{videoId}/stream")
    suspend fun getStreamUrl(@Path("videoId") videoId: String): StreamUrlResponse
}
```

Base URL is stored in `DataStore` (user-configurable) with a default pointing to the deployed server.

### Subtitle Sync Pattern

Use a `LaunchedEffect` loop polling `player.currentPosition` every 100ms.
Binary search the cue list to find the active cue:

```kotlin
// android/ui/player/SubtitleSyncEngine.kt
fun findActiveCue(cues: List<Cue>, positionMs: Long): Cue? {
    var lo = 0; var hi = cues.size - 1
    while (lo <= hi) {
        val mid = (lo + hi) / 2
        val cue = cues[mid]
        when {
            positionMs < cue.startMs -> hi = mid - 1
            positionMs > cue.endMs  -> lo = mid + 1
            else -> return cue
        }
    }
    return null
}
```

### Video Player Setup

```kotlin
// Use Media3 ExoPlayer. Stream URL comes from /api/video/:id/stream (302 → YouTube CDN).
val player = ExoPlayer.Builder(context).build()
val mediaItem = MediaItem.fromUri(streamUrl)
player.setMediaItem(mediaItem)
player.prepare()
player.playWhenReady = true

// Subtitle overlay is a Compose Box with AndroidView for ExoPlayer below
// and subtitle Text composables aligned BottomCenter above it.
```

---

### Feature: Subtitle Visibility Toggles

Users can independently turn the **source subtitle** (e.g. German) and the **translation subtitle** (e.g. English) on or off while the video is playing.

This feature mirrors `showSource` / `showTrans` from the original Node.js `AppContext`.

#### State (PlayerViewModel)

```kotlin
// android/ui/player/PlayerViewModel.kt
data class PlayerUiState(
    // ... other fields ...
    val showSource: Boolean = true,   // show/hide source language subtitle
    val showTrans: Boolean = true,    // show/hide translation subtitle
    val fontSize: FontSize = FontSize.M,
)

enum class FontSize { S, M, L }

class PlayerViewModel @Inject constructor(
    private val settingsRepo: SettingsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    fun toggleSource() {
        val newVal = !_uiState.value.showSource
        _uiState.update { it.copy(showSource = newVal) }
        viewModelScope.launch { settingsRepo.setShowSource(newVal) }
    }

    fun toggleTrans() {
        val newVal = !_uiState.value.showTrans
        _uiState.update { it.copy(showTrans = newVal) }
        viewModelScope.launch { settingsRepo.setShowTrans(newVal) }
    }
}
```

Toggles are persisted to **DataStore** via `SettingsRepository` so they survive app restarts.

#### Subtitle Overlay Rendering

The overlay only renders a subtitle line when both the toggle is ON and there is an active cue:

```kotlin
// android/ui/player/SubtitleOverlay.kt
@Composable
fun SubtitleOverlay(
    sourceCue: Cue?,
    transCue: Cue?,
    showSource: Boolean,
    showTrans: Boolean,
    fontSize: FontSize,
    modifier: Modifier = Modifier,
) {
    val sourceSize = when (fontSize) { FontSize.S -> 13.sp; FontSize.M -> 16.sp; FontSize.L -> 20.sp }
    val transSize  = (sourceSize.value * 0.8f).sp

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Source subtitle (e.g. German) — shown only if toggle ON and cue active
        if (showSource && sourceCue != null) {
            SubtitleLine(text = sourceCue.text, fontSize = sourceSize, isSource = true)
        }
        // Translation subtitle (e.g. English) — shown only if toggle ON and cue active
        if (showTrans && transCue != null) {
            SubtitleLine(text = transCue.text, fontSize = transSize, isSource = false)
        }
    }
}

@Composable
private fun SubtitleLine(text: String, fontSize: TextUnit, isSource: Boolean) {
    Text(
        text = text,
        fontSize = fontSize,
        color = if (isSource) Color.White else Color(0xFFFFD54F),  // white = source, amber = translation
        fontWeight = if (isSource) FontWeight.SemiBold else FontWeight.Normal,
        textAlign = TextAlign.Center,
        style = LocalTextStyle.current.copy(
            shadow = Shadow(color = Color.Black, blurRadius = 6f),  // readability on any background
        ),
        modifier = Modifier
            .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}
```

#### Toggle Buttons UI

Two icon-toggle buttons shown in the player controls bar:

```kotlin
// android/ui/player/PlayerControls.kt  (excerpt)
Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    // Source language toggle (e.g. "DE")
    SubtitleToggleButton(
        label = sourceLang.uppercase(),          // "DE"
        isOn = showSource,
        onClick = onToggleSource,
    )
    // Translation toggle (e.g. "EN")
    SubtitleToggleButton(
        label = transLang.uppercase(),           // "EN"
        isOn = showTrans,
        onClick = onToggleTrans,
    )
}

@Composable
fun SubtitleToggleButton(label: String, isOn: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = isOn,
        onClick = onClick,
        label = { Text(text = if (isOn) "CC $label ✓" else "CC $label ✗", fontSize = 12.sp) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = Color.White,
        ),
    )
}
```

#### DataStore Keys

```kotlin
// android/data/preferences/PreferencesKeys.kt
object PreferencesKeys {
    val SHOW_SOURCE = booleanPreferencesKey("show_source")   // default: true
    val SHOW_TRANS  = booleanPreferencesKey("show_trans")    // default: true
    val FONT_SIZE   = stringPreferencesKey("font_size")      // default: "M"
    val GROQ_API_KEY    = stringPreferencesKey("groq_api_key")
    val SERVER_BASE_URL = stringPreferencesKey("server_base_url")
}
```

#### Edge Cases
- If both toggles are OFF → subtitle overlay composable is hidden entirely (no blank space)
- If video has no `hasDe` or `hasEn` (from meta response) → corresponding toggle is **disabled** (greyed out), not just OFF
- Toggle state is **per-app** (not per-video) — matches the original web app behaviour

---

### Local History (Room)

```kotlin
// android/data/db/HistoryEntity.kt
@Entity(tableName = "history")
data class HistoryEntity(
    @PrimaryKey val videoId: String,
    val title: String,
    val thumbnailUrl: String,
    val watchedAt: Long,  // epoch ms
    val sourceLang: String,
    val transLang: String,
)
```

### Settings (DataStore)

Keys stored in DataStore preferences:
- `groq_api_key` — BYOK Groq key (string)
- `server_base_url` — API server URL
- `font_size` — `S`, `M`, or `L`
- `show_source` — boolean (default `true`) — **source subtitle visibility toggle**
- `show_trans` — boolean (default `true`) — **translation subtitle visibility toggle**

---

## Spring Boot Server

### Location
`server/` — Maven project (Java 21)

### Language
**Java 21** (uses records, sealed classes, pattern matching where appropriate)

### Key Libraries

```xml
<!-- server/pom.xml -->
<dependency>spring-boot-starter-web</dependency>
<dependency>spring-boot-starter-validation</dependency>
<dependency>com.squareup.okhttp3:okhttp:4.x.x</dependency>
<dependency>com.google.code.gson:gson:2.x.x</dependency>
<!-- Rate limiting -->
<dependency>com.github.bucket4j:bucket4j-core:8.x.x</dependency>
```

### Package Structure

```
com.echolingo.server/
├── EchoLingoApplication.java
├── config/
│   ├── AppConfig.java          # @ConfigurationProperties
│   └── CorsConfig.java
├── controller/
│   ├── ImportController.java   # POST /api/import
│   ├── SubtitlesController.java
│   ├── MetaController.java
│   ├── VideoController.java    # GET /api/video/:id/stream → 302
│   └── HealthController.java
├── service/
│   ├── ImportService.java      # Main pipeline (mirrors importService.js)
│   ├── YtdlpService.java       # ProcessBuilder wrapper
│   ├── CaptionsService.java    # Direct YouTube page scrape
│   ├── VttParser.java          # VTT text → List<Cue>
│   ├── TranslatorService.java  # DeepL / Google Translate
│   ├── GroqService.java        # OkHttp multipart → Groq REST
│   ├── ScoringEngine.java      # Pure scoring logic
│   └── CacheService.java       # File-system JSON cache
├── model/
│   ├── Cue.java                # record Cue(int index, double start, double end, String text)
│   ├── VideoMeta.java          # record
│   └── SubtitleScores.java     # record
└── exception/
    ├── AppError.java
    └── GlobalExceptionHandler.java
```

### Import Pipeline (mirrors Node.js `importService.js`)

```
1. Check file cache (server/cache/{videoId}/meta.json)
   └─ if valid version → return cached

2. CaptionsService.fetchPageData(videoId)
   └─ scrapes YouTube page for VTT URLs + metadata

3. Get German cues:
   a. YouTube auto-captions (fast path)
   b. YtdlpService.fetchSubtitles(videoId) with proxy pool
   c. YtdlpService.downloadAudio() → GroqService.transcribeAudio()

4. Get English cues:
   a. YouTube English captions
   b. TranslatorService.translateCues(deCues)  [DeepL or Google]

5. ScoringEngine.computeAllScores(deCues, enCues, duration, enSource)

6. CacheService.write(videoId, meta, deCues, enCues)
```

### YtdlpService Pattern

```java
// server/service/YtdlpService.java
public List<String> buildArgs(String videoId) {
    List<String> args = new ArrayList<>(List.of(
        config.getYtdlpPath(),
        "--write-auto-subs", "--write-subs",
        "--sub-langs", "de,en",
        "--skip-download", "--convert-subs", "vtt",
        "--output", outputTemplate,
        "--no-overwrites"
    ));
    proxyArgs().ifPresent(args::addAll);  // rotating proxy pool
    return args;
}

ProcessBuilder pb = new ProcessBuilder(args);
pb.redirectErrorStream(true);
Process proc = pb.start();
```

### Proxy Pool

`YTDLP_PROXY_LIST` env var — comma-separated list of proxy URLs.
`YtdlpService` picks one at random per call (same logic as Node.js version).

### Configuration (`application.properties`)

```properties
server.port=3001
echolingo.cache-dir=./cache
echolingo.ytdlp-path=yt-dlp
echolingo.ytdlp-timeout-ms=60000
echolingo.ytdlp-proxy-list=
echolingo.deepl-key=
echolingo.groq-api-key=
```

### Cue Model (Java record)

```java
public record Cue(int index, double start, double end, String text) {}
```

`start` and `end` are **seconds** (double), matching the Node.js format.
The Android app converts to milliseconds (`(long)(start * 1000)`).

### API Responses

All responses use the same JSON shape as the Node.js backend so the Android Retrofit client works against either.

```json
// POST /api/import → 200
{
  "videoId": "dQw4w9WgXcQ",
  "title": "...",
  "duration": 212,
  "thumbnailUrl": "...",
  "hasDe": true,
  "hasEn": true,
  "deSource": "youtube_captions",
  "enSource": "youtube_captions_en",
  "scores": { "syncScore": 87, "qualityScore": 91, "translationScore": 90, "overallScore": 89 },
  "cached": false,
  "ready": true
}

// GET /api/subtitles/:id/:lang → 200
[
  { "index": 0, "start": 0.0, "end": 3.5, "text": "Willkommen!" },
  ...
]

// GET /api/video/:id/stream → 302 Location: <youtube-cdn-url>
```

---

## Environment Variables

| Variable | Used by | Purpose |
|---|---|---|
| `PORT` | Server | HTTP port (default 3001) |
| `ECHOLINGO_CACHE_DIR` | Server | Cache directory path |
| `ECHOLINGO_YTDLP_PATH` | Server | Path to yt-dlp binary |
| `ECHOLINGO_YTDLP_PROXY_LIST` | Server | Comma-separated proxy pool |
| `ECHOLINGO_DEEPL_KEY` | Server | DeepL API key (optional) |
| `ECHOLINGO_GROQ_API_KEY` | Server | Server-side Groq key (optional) |

---

## Key Constraints & Gotchas

1. **yt-dlp cannot run on Android** — all subtitle extraction happens on the server.
2. **CORS** — Server must allow Android app's requests. For emulator use `10.0.2.2` instead of `localhost`.
3. **ExoPlayer + YouTube CDN** — The `/api/video/:id/stream` endpoint returns a 302 redirect. ExoPlayer follows redirects automatically.
4. **Subtitle overlay z-order** — ExoPlayer's `PlayerView` must be below the subtitle `Box` in the Compose tree. Use `Box { AndroidView(...); SubtitleOverlay() }`.
5. **VTT timestamps** — Format is `HH:MM:SS.mmm`. Parser must handle both `MM:SS.mmm` and `HH:MM:SS.mmm` formats.
6. **Cue deduplication** — YouTube auto-captions often repeat the same line across consecutive cues. The VTT parser strips duplicate consecutive cues.
7. **Cache version** — Current cache version is `4`. On version mismatch, re-process even if cache files exist.
8. **Google Translate batching** — Uses `|||` as a separator to batch 80 cues per HTTP call. The `|||` regex must survive Google's translation.

---

## Running Locally

### Server
```bash
cd server
mvn spring-boot:run
# or
mvn package && java -jar target/echolingo-server-*.jar
```
Server runs on `http://localhost:3001`

### Android (in Android Studio)
1. Set server URL in app settings to `http://10.0.2.2:3001` (emulator) or your machine's LAN IP (physical device)
2. Run on emulator or device via Android Studio

### Docker (Server)
```bash
docker build -f server/Dockerfile -t echolingo-server .
docker run -p 3001:3001 -e ECHOLINGO_YTDLP_PATH=yt-dlp echolingo-server
```

---

## Testing

### Server Unit Tests (JUnit 5 + Mockito)
- `VttParserTest` — test VTT parsing edge cases
- `ScoringEngineTest` — verify score calculations
- `SubtitleSyncTest` — binary search correctness

### Android Unit Tests (JUnit 4 + Mockito)
- `SubtitleSyncEngineTest` — test `findActiveCue()` binary search
- `ViewModelTests` — test state transitions

### Android Instrumentation Tests (Espresso / Compose Test)
- Home screen: enter URL → verify loading state
- Player screen: verify subtitle overlay appears

---

## Code Style

### Kotlin (Android)
- Use `data class` for UI state, `sealed class` for UI events
- `StateFlow` for all ViewModel state (never `LiveData`)
- `suspend fun` + coroutines everywhere (no RxJava)
- Prefer `@Composable` functions with single responsibility
- Name conventions: `Screen` suffix for composable screens, `ViewModel` suffix for VMs

### Java (Server)
- Use Java records for immutable data models (`Cue`, `VideoMeta`, etc.)
- `@Service`, `@RestController`, `@Value` for Spring idioms
- All service methods return typed results or throw `AppError` (which `GlobalExceptionHandler` converts to HTTP responses)
- No `null` return values — use `Optional<T>` where absence is valid

---

## Feature: Shadowing Mode (Speak-Along / Repeat-After-Me)

### What It Does

When **Shadowing Mode** is ON:
1. The video plays normally, showing dual subtitles.
2. At the **end of each subtitle cue**, the video **automatically pauses**.
3. The app shows a microphone recording UI — the user speaks the same line they just heard.
4. The app records the user's voice, sends it to **Groq Whisper** for transcription.
5. The transcription is **fuzzy-matched** against the original subtitle cue text.
6. **If match ≥ 70%** → video resumes automatically. User gets a success ✅ indicator.
7. **If match < 70%** → app shows what the user said vs what they should say. Options: "Try Again" or "Skip".

This technique is called **language shadowing** — proven to improve pronunciation and speaking fluency.

---

### Architecture Overview

```
Video plays cue
       │
       ▼ (cue.endMs reached)
player.pause()
       │
       ▼
ShadowingRecorder.startRecording()   ← Android mic (AudioRecord)
       │  (user speaks — max 8 seconds, auto-stop on silence)
       ▼
ShadowingRecorder.stopRecording() → WAV/M4A file
       │
       ▼
POST /api/transcribe  { audioBase64, lang }   ← Spring Boot server
       │
       ▼
GroqService.transcribeAudio(audioPath, lang)  ← Groq Whisper API
       │
       ▼
SimilarityEngine.score(userText, cueText)     ← on Android (no network needed)
       │
   ┌───┴───┐
  ≥70%    <70%
   │        │
resume    show feedback
video     + retry/skip
```

> **Why Groq Whisper instead of Android SpeechRecognizer?**
> Android's built-in speech recognition is English-only and inaccurate for non-native accents.
> Groq Whisper supports German, French, Japanese, etc. and handles accented speech well.

---

### New Server Endpoint

```
POST /api/transcribe
Content-Type: application/json

{
  "audioBase64": "<base64-encoded WAV/M4A>",
  "lang": "de"              // language code for Whisper hint
}

→ 200 OK
{
  "transcript": "Guten Morgen, wie geht es Ihnen?"
}
```

#### Java Implementation (Spring Boot)

```java
// server/controller/TranscribeController.java
@RestController
@RequestMapping("/api/transcribe")
public class TranscribeController {

    private final GroqService groqService;

    @PostMapping
    public ResponseEntity<TranscribeResponse> transcribe(
            @RequestBody TranscribeRequest req) throws Exception {

        // Decode base64 audio → temp file
        byte[] audioBytes = Base64.getDecoder().decode(req.audioBase64());
        Path tempFile = Files.createTempFile("shadow_", ".m4a");
        Files.write(tempFile, audioBytes);

        try {
            String transcript = groqService.transcribeAudio(
                tempFile.toString(), req.lang(), null);
            return ResponseEntity.ok(new TranscribeResponse(transcript));
        } finally {
            Files.deleteIfExists(tempFile);  // always clean up temp file
        }
    }
}

// Records
record TranscribeRequest(String audioBase64, String lang) {}
record TranscribeResponse(String transcript) {}
```

---

### Android: Recording

Use **`AudioRecord`** (raw PCM → WAV) or **`MediaRecorder`** (direct M4A output).
`MediaRecorder` is simpler and produces M4A directly for Groq.

```kotlin
// android/ui/player/shadowing/ShadowingRecorder.kt
class ShadowingRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null

    fun startRecording(): File {
        outputFile = File(context.cacheDir, "shadow_${System.currentTimeMillis()}.m4a")
        recorder = MediaRecorder(context).apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(16000)   // Whisper prefers 16kHz
            setAudioEncodingBitRate(64000)
            setMaxDuration(8000)          // 8 second max per cue
            setOutputFile(outputFile!!.absolutePath)
            prepare()
            start()
        }
        return outputFile!!
    }

    fun stopRecording(): File? {
        recorder?.apply { stop(); release() }
        recorder = null
        return outputFile
    }

    fun cleanup() {
        outputFile?.delete()
        outputFile = null
    }
}
```

**Silence detection**: Register `setOnInfoListener` for `MEDIA_RECORDER_INFO_MAX_DURATION_REACHED`
to auto-stop after 8s. Optionally add amplitude polling every 500ms — if amplitude < threshold
for 1.5s → auto-stop (user has finished speaking).

---

### Android: Similarity Engine

Runs **on-device** — no extra network call needed. Uses **Levenshtein distance** normalized
to a 0–100 similarity score, with light text preprocessing.

```kotlin
// android/domain/SimilarityEngine.kt
object SimilarityEngine {

    /**
     * Returns a similarity score 0–100 between user's spoken text and the target cue.
     * 70+ is considered a successful match.
     */
    fun score(userText: String, targetText: String): Int {
        val u = normalize(userText)
        val t = normalize(targetText)
        if (t.isEmpty()) return 100
        val distance = levenshtein(u, t)
        val maxLen = maxOf(u.length, t.length)
        return ((1.0 - distance.toDouble() / maxLen) * 100).toInt().coerceIn(0, 100)
    }

    private fun normalize(text: String): String =
        text.lowercase()
            .replace(Regex("[.,!?;:\"'\\-]"), "")  // strip punctuation
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun levenshtein(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) { 0 } }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                dp[i][j] = if (a[i-1] == b[j-1]) dp[i-1][j-1]
                            else 1 + minOf(dp[i-1][j], dp[i][j-1], dp[i-1][j-1])
            }
        }
        return dp[a.length][b.length]
    }
}
```

**Threshold**: `PASS_THRESHOLD = 70` — configurable in settings.

---

### Android: Shadowing State Machine

```kotlin
// android/ui/player/shadowing/ShadowingState.kt
sealed class ShadowingState {
    object Idle : ShadowingState()          // shadowing mode off
    object Watching : ShadowingState()      // video playing, waiting for cue end
    object Recording : ShadowingState()     // mic active, user speaking
    object Analyzing : ShadowingState()     // audio uploaded, waiting for Groq
    data class Result(
        val score: Int,
        val userSaid: String,
        val targetText: String,
        val passed: Boolean,                // score >= PASS_THRESHOLD
    ) : ShadowingState()
    object Skipped : ShadowingState()       // user tapped "Skip"
}
```

#### ViewModel (excerpt)

```kotlin
// android/ui/player/PlayerViewModel.kt  (shadowing additions)

private val PASS_THRESHOLD = 70

var shadowingEnabled by mutableStateOf(false)
    private set

private val _shadowingState = MutableStateFlow<ShadowingState>(ShadowingState.Idle)
val shadowingState: StateFlow<ShadowingState> = _shadowingState

fun toggleShadowingMode() {
    shadowingEnabled = !shadowingEnabled
    _shadowingState.value = if (shadowingEnabled) ShadowingState.Watching else ShadowingState.Idle
}

// Called by subtitle sync when a cue ends and shadowing is enabled
fun onCueEnded(cue: Cue) {
    if (!shadowingEnabled) return
    player.pause()
    _shadowingState.value = ShadowingState.Recording
    viewModelScope.launch {
        val audioFile = recorder.startRecording()
        delay(8000)  // max recording duration
        val file = recorder.stopRecording() ?: return@launch
        _shadowingState.value = ShadowingState.Analyzing

        // Upload to server for Groq transcription
        val transcript = shadowingRepo.transcribe(file, sourceLang)
        val score = SimilarityEngine.score(transcript, cue.text)
        val passed = score >= PASS_THRESHOLD

        _shadowingState.value = ShadowingState.Result(score, transcript, cue.text, passed)

        if (passed) {
            delay(1200)   // show success briefly
            player.play()
            _shadowingState.value = ShadowingState.Watching
        }
    }
}

fun skipCue() {
    recorder.stopRecording()
    _shadowingState.value = ShadowingState.Skipped
    player.play()
    viewModelScope.launch {
        delay(500)
        _shadowingState.value = ShadowingState.Watching
    }
}

fun retryRecording(cue: Cue) {
    onCueEnded(cue)  // restart recording for same cue
}
```

---

### Android: Shadowing UI Overlay

Appears on top of the paused video when shadowing mode is active:

```kotlin
// android/ui/player/shadowing/ShadowingOverlay.kt
@Composable
fun ShadowingOverlay(
    state: ShadowingState,
    onSkip: () -> Unit,
    onRetry: () -> Unit,
) {
    AnimatedVisibility(
        visible = state !is ShadowingState.Idle && state !is ShadowingState.Watching,
        enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
        exit  = fadeOut(),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.75f))
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            when (state) {
                is ShadowingState.Recording -> RecordingUI(onSkip)
                is ShadowingState.Analyzing -> AnalyzingUI()
                is ShadowingState.Result    -> ResultUI(state, onRetry, onSkip)
                else -> {}
            }
        }
    }
}

@Composable
private fun RecordingUI(onSkip: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // Animated mic icon (pulsing red circle)
        MicPulseAnimation()
        Spacer(Modifier.height(12.dp))
        Text("🎤 Speak now...", color = Color.White, fontSize = 18.sp)
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onSkip) {
            Text("Skip", color = Color(0xFFFFD54F))
        }
    }
}

@Composable
private fun ResultUI(state: ShadowingState.Result, onRetry: () -> Unit, onSkip: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // Score ring (e.g. 85/100 in green, 45/100 in red)
        ScoreRing(score = state.score, passed = state.passed)
        Spacer(Modifier.height(12.dp))
        if (state.passed) {
            Text("✅ Great job!", color = Color(0xFF69F0AE), fontSize = 18.sp)
        } else {
            Text("You said:", color = Color.Gray, fontSize = 13.sp)
            Text("\"${state.userSaid}\"", color = Color.White, fontSize = 15.sp)
            Spacer(Modifier.height(6.dp))
            Text("Target:", color = Color.Gray, fontSize = 13.sp)
            Text("\"${state.targetText}\"", color = Color(0xFFFFD54F), fontSize = 15.sp)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onRetry) { Text("🔁 Try Again") }
                TextButton(onClick = onSkip)     { Text("Skip →", color = Color.Gray) }
            }
        }
    }
}
```

---

### Settings for Shadowing Mode

Additional DataStore keys:

```kotlin
val SHADOWING_ENABLED       = booleanPreferencesKey("shadowing_enabled")       // default: false
val SHADOWING_THRESHOLD     = intPreferencesKey("shadowing_threshold")         // default: 70
val SHADOWING_MAX_SECONDS   = intPreferencesKey("shadowing_max_seconds")       // default: 8
val SHADOWING_AUTO_CONTINUE = booleanPreferencesKey("shadowing_auto_continue") // default: true
```

`SHADOWING_AUTO_CONTINUE = false` → even on pass, wait for user to tap "Continue" (useful for slow learners).

---

### Required Android Permissions

Add to `android/app/src/main/AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.INTERNET" />
```

Request `RECORD_AUDIO` at runtime (Android 6+) before starting shadowing mode.
Use `rememberPermissionState(Manifest.permission.RECORD_AUDIO)` from Accompanist.

---

### Key Constraints & Edge Cases

| Situation | Behaviour |
|---|---|
| Groq key not set | Shadowing mode is disabled (greyed out toggle). Show "Add Groq API key in Settings" toast |
| Network error during transcription | Show "Connection error" in result UI. Offer Retry |
| User silent (no speech detected) | Auto-stop after `SHADOWING_MAX_SECONDS`. Score = 0, show result UI |
| Very short cue (< 1s) | Skip shadowing for that cue, auto-continue (too short to repeat meaningfully) |
| Shadowing mode + subtitle toggles | Shadowing always shows the cue text in the result UI regardless of `showSource`/`showTrans` |
| User taps back during recording | Cancel recording, delete temp file, resume video normally |

---

## Feature: Dictionary Vault (Word Saver)

### What It Does

While watching a video, the user can **long-press any word** in the subtitle overlay.
A bottom sheet appears with the word's translation + context.
The user taps **"Add to Vault"** and the word is saved to their personal dictionary.

A dedicated **Dictionary Vault screen** lets users browse, search, and review all saved words.
Each entry stores: the word, translation, the full sentence it came from, the video it was in, and the timestamp.

**Example:**
```
Subtitle shown: "Guten Morgen, wie geht es Ihnen?"
User long-presses: "Morgen"
Bottom sheet:
  🇩🇪  Morgen          🇬🇧  Morning
  Context: "Guten Morgen, wie geht es Ihnen?"
  From: "German for Beginners" · 0:32
  [Add to Vault ✚]   [Cancel]
```

---

### User Flow

```
1. Subtitle renders word-by-word as individually tappable spans
2. User long-presses a word → video auto-pauses
3. Bottom sheet appears:
     - Word (highlighted) + translation (fetched from /api/word/translate)
     - Full sentence context
     - Video title + timestamp
     - [Add to Vault] button
4. Tap "Add to Vault" → saved to Room DB → "Morgen added to Vault ✚" toast
5. Video resumes
6. Later: user opens Dictionary Vault screen → browses all saved words
```

---

### Room Database: DictionaryEntry

```kotlin
// android/data/db/DictionaryEntry.kt
@Entity(tableName = "dictionary")
data class DictionaryEntry(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val word: String,               // "Morgen"
    val translation: String,        // "Morning"
    val sourceLang: String,         // "de"
    val targetLang: String,         // "en"

    // Context
    val contextSentence: String,    // "Guten Morgen, wie geht es Ihnen?"
    val videoId: String,            // YouTube video ID
    val videoTitle: String,         // "German for Beginners - Ep 1"
    val videoTimestampMs: Long,     // 32000  (ms when word was saved)

    // Metadata
    val savedAt: Long,              // System.currentTimeMillis()
    val notes: String = "",         // user-editable notes

    // Review tracking (for future flashcard mode)
    val reviewCount: Int = 0,
    val lastReviewedAt: Long = 0,
)
```

#### DAO

```kotlin
// android/data/db/DictionaryDao.kt
@Dao
interface DictionaryDao {
    @Query("SELECT * FROM dictionary ORDER BY savedAt DESC")
    fun getAllWords(): Flow<List<DictionaryEntry>>

    @Query("SELECT * FROM dictionary WHERE sourceLang = :lang ORDER BY savedAt DESC")
    fun getWordsByLang(lang: String): Flow<List<DictionaryEntry>>

    @Query("SELECT * FROM dictionary WHERE word LIKE '%' || :query || '%' OR translation LIKE '%' || :query || '%'")
    fun searchWords(query: String): Flow<List<DictionaryEntry>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWord(entry: DictionaryEntry): Long

    @Delete
    suspend fun deleteWord(entry: DictionaryEntry)

    @Query("SELECT COUNT(*) FROM dictionary")
    fun getTotalCount(): Flow<Int>

    @Query("SELECT EXISTS(SELECT 1 FROM dictionary WHERE word = :word AND sourceLang = :lang)")
    suspend fun isWordSaved(word: String, lang: String): Boolean
}
```

---

### New Server Endpoint: Single Word Translation

```
POST /api/word/translate
Content-Type: application/json

{
  "word": "Morgen",
  "sourceLang": "de",
  "targetLang": "en",
  "context": "Guten Morgen, wie geht es Ihnen?"   // optional — improves accuracy
}

→ 200 OK
{
  "word": "Morgen",
  "translation": "morning",
  "sourceLang": "de",
  "targetLang": "en"
}
```

The `context` field is passed to DeepL (it supports context-aware translation).
For the free Google Translate fallback, only the word itself is translated.

#### Java Implementation (Spring Boot)

```java
// server/controller/WordTranslateController.java
@RestController
@RequestMapping("/api/word")
public class WordTranslateController {

    private final TranslatorService translatorService;

    @PostMapping("/translate")
    public ResponseEntity<WordTranslateResponse> translate(
            @RequestBody @Valid WordTranslateRequest req) {

        // Reuse existing TranslatorService — translate a single-cue array
        Cue singleCue = new Cue(0, 0.0, 1.0, req.word());
        List<Cue> translated = translatorService.translateCues(
            List.of(singleCue), req.sourceLang(), req.targetLang());

        String translation = translated.isEmpty()
            ? req.word()
            : translated.get(0).text();

        return ResponseEntity.ok(new WordTranslateResponse(
            req.word(), translation, req.sourceLang(), req.targetLang()));
    }
}

// Records
record WordTranslateRequest(
    @NotBlank String word,
    @NotBlank String sourceLang,
    @NotBlank String targetLang,
    String context   // nullable
) {}

record WordTranslateResponse(
    String word, String translation, String sourceLang, String targetLang) {}
```

---

### Android: Word-Level Long-Press in Subtitle

Standard Compose `Text` does not support per-word long-press detection.
Use **`FlowRow`** (Compose Foundation) with individual word `Text` composables,
each having `Modifier.combinedClickable(onLongClick = ...)`.

```kotlin
// android/ui/player/TappableSubtitleLine.kt
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TappableSubtitleLine(
    text: String,
    fontSize: TextUnit,
    color: Color,
    fontWeight: FontWeight,
    onWordLongPress: (word: String, sentence: String) -> Unit,
) {
    // Split text into words, preserving punctuation per word
    val rawWords = text.split(" ").filter { it.isNotEmpty() }

    FlowRow(
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth(),
    ) {
        rawWords.forEach { rawWord ->
            val cleanWord = rawWord.trimEnd { !it.isLetterOrDigit() }  // strip trailing . , ?

            Text(
                text = "$rawWord ",   // space between words
                fontSize = fontSize,
                color = color,
                fontWeight = fontWeight,
                style = LocalTextStyle.current.copy(
                    shadow = Shadow(color = Color.Black, blurRadius = 6f),
                ),
                modifier = Modifier
                    .combinedClickable(
                        onClick = {},
                        onLongClick = {
                            if (cleanWord.isNotEmpty()) {
                                onWordLongPress(cleanWord, text)
                            }
                        },
                        onLongClickLabel = "Save word '$cleanWord' to dictionary",
                    )
                    .padding(horizontal = 1.dp),
            )
        }
    }
}
```

Replace the plain `SubtitleLine` in `SubtitleOverlay.kt` with `TappableSubtitleLine`
and pass `onWordLongPress` up to the `PlayerScreen` → `PlayerViewModel`.

---

### Android: Word Detail Bottom Sheet

Shown immediately after long-press. Video auto-pauses while sheet is open.

```kotlin
// android/ui/player/dictionary/WordDetailSheet.kt
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WordDetailSheet(
    word: String,
    sentence: String,
    videoTitle: String,
    timestampMs: Long,
    uiState: WordDetailUiState,       // Idle | Loading | Loaded | Error | Saved
    onAddToVault: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {

            // Word + translation row
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("🇩🇪  $word", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    when (uiState) {
                        is WordDetailUiState.Loading ->
                            CircularProgressIndicator(Modifier.size(16.dp))
                        is WordDetailUiState.Loaded  ->
                            Text("🇬🇧  ${uiState.translation}",
                                fontSize = 18.sp, color = Color(0xFFFFD54F))
                        is WordDetailUiState.Error   ->
                            Text("Translation unavailable", color = Color.Gray)
                        else -> {}
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Divider()
            Spacer(Modifier.height(12.dp))

            // Context sentence with the target word highlighted
            HighlightedSentence(sentence = sentence, highlight = word)

            Spacer(Modifier.height(4.dp))
            Text(
                "From: $videoTitle · ${formatTimestamp(timestampMs)}",
                fontSize = 12.sp, color = Color.Gray,
            )

            Spacer(Modifier.height(20.dp))

            // Add to Vault button
            val isSaved = uiState is WordDetailUiState.Saved
            Button(
                onClick = onAddToVault,
                enabled = uiState is WordDetailUiState.Loaded && !isSaved,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isSaved) Color(0xFF388E3C) else MaterialTheme.colorScheme.primary
                ),
            ) {
                Text(if (isSaved) "✓ Saved to Vault" else "Add to Vault  ✚")
            }
        }
    }
}

// Highlights the target word in amber within the sentence
@Composable
fun HighlightedSentence(sentence: String, highlight: String) {
    val annotated = buildAnnotatedString {
        val lower = sentence.lowercase()
        val idx = lower.indexOf(highlight.lowercase())
        if (idx >= 0) {
            append(sentence.substring(0, idx))
            withStyle(SpanStyle(color = Color(0xFFFFD54F), fontWeight = FontWeight.Bold)) {
                append(sentence.substring(idx, idx + highlight.length))
            }
            append(sentence.substring(idx + highlight.length))
        } else {
            append(sentence)
        }
    }
    Text(annotated, fontSize = 14.sp, textAlign = TextAlign.Center)
}
```

#### ViewModel for Word Detail

```kotlin
// android/ui/player/dictionary/WordDetailViewModel.kt
sealed class WordDetailUiState {
    object Idle    : WordDetailUiState()
    object Loading : WordDetailUiState()
    data class Loaded(val translation: String) : WordDetailUiState()
    object Error   : WordDetailUiState()
    object Saved   : WordDetailUiState()
}

class WordDetailViewModel @Inject constructor(
    private val dictionaryRepo: DictionaryRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<WordDetailUiState>(WordDetailUiState.Idle)
    val state: StateFlow<WordDetailUiState> = _state

    fun loadTranslation(word: String, sentence: String, sourceLang: String, targetLang: String) {
        viewModelScope.launch {
            _state.value = WordDetailUiState.Loading
            try {
                val result = dictionaryRepo.translateWord(word, sourceLang, targetLang, sentence)
                _state.value = WordDetailUiState.Loaded(result.translation)
            } catch (e: Exception) {
                _state.value = WordDetailUiState.Error
            }
        }
    }

    fun addToVault(
        word: String, translation: String, sourceLang: String, targetLang: String,
        sentence: String, videoId: String, videoTitle: String, timestampMs: Long,
    ) {
        viewModelScope.launch {
            dictionaryRepo.saveWord(
                DictionaryEntry(
                    word = word, translation = translation,
                    sourceLang = sourceLang, targetLang = targetLang,
                    contextSentence = sentence,
                    videoId = videoId, videoTitle = videoTitle,
                    videoTimestampMs = timestampMs,
                    savedAt = System.currentTimeMillis(),
                )
            )
            _state.value = WordDetailUiState.Saved
        }
    }
}
```

---

### Dictionary Vault Screen

A dedicated screen showing all saved words.

```kotlin
// android/ui/dictionary/DictionaryVaultScreen.kt
// Navigation route: /dictionary
```

**Layout:**
```
┌─────────────────────────────────────────┐
│  🗂 Dictionary Vault          [Search 🔍]│
│  42 words saved                          │
├─────────────────────────────────────────┤
│  [All] [DE→EN] [FR→EN]  (lang filter)   │
├─────────────────────────────────────────┤
│ ┌─────────────────────────────────────┐ │
│ │ Morgen          Morning             │ │
│ │ "Guten Morgen, wie geht es Ihnen?" │ │
│ │ 📹 German Ep.1 · 0:32   🗑         │ │
│ └─────────────────────────────────────┘ │
│ ┌─────────────────────────────────────┐ │
│ │ Schmetterling   Butterfly           │ │
│ │ "Der Schmetterling fliegt hoch."   │ │
│ │ 📹 Nature Doc · 1:14    🗑         │ │
│ └─────────────────────────────────────┘ │
│                  ...                    │
└─────────────────────────────────────────┘
```

Tap any card → opens the video at the saved timestamp (deep-links to PlayerScreen).
Swipe left on a card → delete from vault.

---

### Edge Cases

| Situation | Behaviour |
|---|---|
| Word already in vault | Bottom sheet shows "✓ Already Saved" instead of "Add to Vault" (check with `isWordSaved()`) |
| Translation fetch fails (no network) | Show "Translation unavailable — save anyway?" with fallback button |
| Word is punctuation only (`?`, `.`) | Long-press ignored — `cleanWord.isEmpty()` check prevents trigger |
| Very short word (1-2 chars like "a", "I") | Still saved — user's choice |
| Shadowing mode + long-press | Long-press works normally; sheet pauses video; closing sheet does NOT auto-resume if shadowing was mid-recording |
| Same word, different videos | Stored as separate entries (different `videoId` + `timestampMs`) |
| User edits notes | `notes` field is editable in-card via a small text field on the Vault screen |
