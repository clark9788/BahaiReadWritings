# BahaiReadWritings App — Design & Implementation Plan

## Context

New Android app for daily reading of Bahá'í texts. The corpus (90 XHTML files, ~19 MB) and manifest.csv already exist in `data/corpus/`. The Kotlin Android scaffolding exists but has no source files yet. BahaiResearchA (Java Android) is the closest reference — its WebView + XHTML anchor-ID pattern for scrolling to a saved position transfers directly.

**corpus.db is NOT used** — the full-text search database belongs to BahaiResearchA. For this reading app, `manifest.csv` (filename, title, author, source_format) is sufficient to drive the spinners, and the XHTML files handle all display. This keeps the app footprint at ~19 MB instead of ~57 MB.

---

## Technology Decision

**Kotlin, traditional Views (XML layouts).** Not Compose, not Python.

- Project already Kotlin-scaffolded; manifest.csv + xhtml files are all the data needed
- Python (Kivy/BeeWare) has no real WebView — xhtml rendering would need custom work
- WebView gives a native XHTML display identical to BahaiResearchA; Compose adds an `AndroidView` wrapper but no real benefit for 2 screens
- Threading pattern mirrors BahaiResearchA: ExecutorService + Handler (no coroutines dependency needed)

---

## Architecture

Two activities:

```
MainActivity     — Author spinner → Title spinner → Open button
ReaderActivity   — Full-screen WebView + Mark/Reset toolbar actions
```

### Source files to create

```
app/src/main/java/com/bahairesearch/bahaireadwritings/
  MainActivity.kt           — spinners (from manifest.csv), Open button
  ReaderActivity.kt         — WebView, long-press + toolbar mark, JS bridge
  data/
    ManifestReader.kt       — parse manifest.csv from assets into author/title maps
    BookmarkDatabase.kt     — Room database
    Bookmark.kt             — @Entity: filename (PK), anchorId, savedAt
    BookmarkDao.kt          — upsert, queryByFilename, deleteByFilename

app/src/main/res/layout/
  activity_main.xml         — LinearLayout: title, spinner(author), spinner(title), button
  activity_reader.xml       — CoordinatorLayout: Toolbar + WebView

app/src/main/assets/
  curated/en/html/          — 90 XHTML files (copy from data/corpus/curated/en/html/)
  manifest.csv              — copy from data/corpus/curated/en/manifest.csv
```

---

## Key Implementation Details

### 1. Build fixes (before any source)
- Add Kotlin Android plugin to `libs.versions.toml`:
  `kotlin-android = { id = "org.jetbrains.kotlin.android", version = "2.1.20" }`
- Apply it in root and app `build.gradle.kts`
- Add to `libs.versions.toml`: Room runtime, Room compiler (KSP), KSP plugin
- Add to `app/build.gradle.kts`: Room deps + KSP block
- Declare `MainActivity` in `AndroidManifest.xml` as launcher activity

### 2. ManifestReader.kt
Parse `manifest.csv` from assets on background thread at startup:
- **Filter out non-xhtml entries** — 4 rows have `source_format` of `docx` or `pdf` and have no
  corresponding xhtml file. Skip any row where `source_format != "xhtml"`:
  - `framework-action.pdf` (Universal House of Justice)
  - `muhj-1963-1986.docx` (Universal House of Justice)
  - `muhj-1986-2001.pdf` (Universal House of Justice)
  - `muhj-2001-2022.docx` (Universal House of Justice)
- Build `Map<String, List<Pair<String,String>>>` — author → list of (title, filename)
- Hard-code author order matching BahaiResearchA canonical list
- Return a data class `CorpusIndex(authors, titlesFor, fileFor)`

### 3. MainActivity.kt
- Load CorpusIndex on ExecutorService at startup; post to UI via Handler
- Author spinner: hardcoded canonical order (Baha'u'llah, Bab, 'Abdu'l-Baha, Shoghi Effendi,
  Universal House of Justice, Compilation)
- On author select: populate Title spinner from CorpusIndex; disable until loaded
- "Open" button: look up filename from CorpusIndex, start ReaderActivity with extras:
  `filename`, `title`
- Status text: "Select an author above" → "Select a title" → enabled

### 4. ReaderActivity.kt
- Receive `filename` and `title` extras
- Load WebView: `file:///android_asset/curated/en/html/{filename}`
- On launch: query BookmarkDao for this filename; if found, append `#{anchorId}` to URL
- `WebViewClient.onPageFinished`: if anchor, inject JS:
  ```javascript
  document.getElementById('{anchorId}').scrollIntoView({block:'center'})
  ```
- **Mark Position** (toolbar button + long press):
  - Evaluate JS to find nearest anchor above viewport midpoint:
    ```javascript
    (function(){
      var mid = window.scrollY + window.innerHeight/2;
      var best=null, dist=Infinity;
      document.querySelectorAll('[id]').forEach(function(el){
        var t=el.getBoundingClientRect().top+window.scrollY;
        if(t<=mid && (mid-t)<dist){dist=mid-t; best=el.id;}
      });
      return best;
    })()
    ```
  - Save `Bookmark(filename, anchorId, System.currentTimeMillis())` via Room
  - Show Snackbar: "Position marked"
- **Reset to Beginning** (toolbar overflow menu):
  - AlertDialog: "Reset to the beginning of this text? This will clear your saved position."
  - On confirm: delete bookmark → reload URL without anchor
- Back: standard finish()

### 5. Bookmark.kt / BookmarkDao.kt
```kotlin
@Entity data class Bookmark(
    @PrimaryKey val filename: String,
    val anchorId: String,
    val savedAt: Long
)

@Dao interface BookmarkDao {
    @Insert(onConflict = REPLACE) fun save(b: Bookmark)
    @Query("SELECT * FROM bookmarks WHERE filename = :f") fun get(f: String): Bookmark?
    @Query("DELETE FROM bookmarks WHERE filename = :f") fun delete(f: String)
}
```

---

## UI Layout (consistent with BahaiResearchA style)

**MainActivity:**
```
  Bahá'í Daily Readings         ← app title TextView
  [Author Spinner           ▼]
  [Title Spinner (disabled) ▼]
  [        Open             ]
  Status: "Select an author above"
```

**ReaderActivity:**
```
  [←]  Text Title    [Mark Position]  [⋮ Reset to Beginning]
  ──────────────────────────────────────────────────────────
  │                                                        │
  │   Full-screen WebView (XHTML rendered natively)        │
  │                                                        │
  │   Long press anywhere → mark position                  │
  └────────────────────────────────────────────────────────┘
```

---

## Verification

1. `./gradlew assembleDebug` — confirms Kotlin plugin + Room compile cleanly
2. App launches → spinner populates with 6 authors
3. Select "Baha'u'llah" → Title spinner populates with his works from manifest.csv
4. Select a title → Open → WebView renders XHTML from assets
5. Scroll partway → toolbar "Mark Position" → Snackbar confirms
6. Back → reopen same title → scrolls to marked position automatically
7. Toolbar → Reset → confirm dialog → page reloads from top; reopen confirms cleared
8. Long press on WebView → same mark-position behavior as toolbar button
9. Repeat with a second title → verify bookmarks are independent per file
