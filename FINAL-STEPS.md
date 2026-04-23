# Pulseboard — Final browser steps

Everything the APIs let us do is done. These remaining steps require a browser because Google forbids automation for legal/compliance attestation.

## Firebase App Distribution: READY NOW (zero browser)
Testers already onboarded. Ship new builds any time:
```bash
cd /Users/chinmayramraika/AndroidStudioProjects/pulseboard
make distribute TESTERS=foo@example.com,bar@example.com
```

## Public Play Store launch — ~30 min browser, one-time forever

Do these ONLY if you want public distribution on the Play Store. Pre-release testing happens entirely via Firebase (above) and requires nothing from Play Console.

### 1. Create app in Play Console (~1 min)
[play.google.com/console](https://play.google.com/console) → **Create app**:
- App name: `Pulseboard`
- Default language: English (United States)
- App or game: App
- Free or paid: Free
- Package name: `com.vagarylabs.pulseboard`
- Accept declarations → **Create**

### 2. Fill "Set up your app" declarations (~25–30 min)
Open `docs/play/DECLARATIONS.md` in this repo — all answers pre-drafted. Transcribe into Play Console forms.

### 3. Paste privacy policy URL (~30 sec)
Play Console → **App content** → **Privacy policy** → paste:
```
https://cramraika.github.io/pulseboard/play/PRIVACY.html
```
_(Hosted via GitHub Pages on `Cramraika/pulseboard`.)_

### 4. Pick category + tags (~1 min)
Play Console → **Grow** → **Store settings** → Category: **Tools** (adjust to your app's actual primary function).

### 5. First upload + rollout click (~2 min)
```bash
make release-internal
```
Then **Play Console → Internal testing → Releases** → **Review release** → **Start rollout to Internal testing**. This is the one-time click that exits Play's "draft app" state for this app.

### 6. Production promotion flow
```bash
make promote-alpha              # → Google review (hours–days first time)
make promote-beta
make promote-prod PCT=0.05      # staged 5% production rollout
make rollout PCT=0.5            # widen
make rollout PCT=1.0            # full
```

## Reference
- `docs/play/NEW_APP_RUNBOOK.md` — master runbook
- `docs/play/PLAY_CHECKLIST.md` — Google's checklist mapped to automation tier
- `docs/play/FIREBASE_SETUP.md` — Firebase App Distribution runbook
- `docs/play/AUTOMATION_ENVELOPE.md` — what's automatable and what's not
- `RELEASING.md` — lifecycle + emergency playbook
