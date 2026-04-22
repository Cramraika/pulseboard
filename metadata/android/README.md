# Play Store Metadata

This directory is the source of truth for Pulseboard's Play Store listing.
Layout mirrors `fastlane supply` conventions, so `~/.claude/scripts/google-play-publisher.py sync-listing` (and fastlane, if ever installed) read it identically.

## Layout

```
metadata/android/
  <locale>/                       e.g. en-US, hi-IN
    title.txt                     max 30 chars
    short_description.txt         max 80 chars
    full_description.txt          max 4000 chars
    video.txt                     optional YouTube URL
    changelogs/<versionCode>.txt  max 500 chars; release notes, per versionCode
    images/
      icon.png                    512x512 PNG
      featureGraphic.png          1024x500 PNG
      phoneScreenshots/*.png      2–8 screenshots
      sevenInchScreenshots/*.png  optional tablet shots
      tenInchScreenshots/*.png    optional tablet shots
```

## Sync

```bash
make sync-listing       # all locales under metadata/android/
make sync-listing LANG=en-US   # one locale only
make sync-listing SKIP_IMAGES=1  # text only, faster
```

Every sync replaces the Play-side copy with what's on disk. Treat Play Console as a read-only mirror.
