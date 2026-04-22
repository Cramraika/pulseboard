# Pulseboard — Releasing to Play Store

Everything is driven by `make` targets backed by `~/.claude/scripts/google-play-publisher.py`. You should never need to open Play Console for a routine release.

## One-time setup (per machine)

1. **Service account JSON** at `~/.config/google-play/sa.json` (chmod 600). For this workstation, already in place.
2. **Keystore props** in `~/.gradle/gradle.properties`:
   - `PULSEBOARD_KEYSTORE_PATH`, `PULSEBOARD_KEYSTORE_PASSWORD`, `PULSEBOARD_KEY_ALIAS`, `PULSEBOARD_KEY_PASSWORD`
3. **Play Developer API** enabled for the `vagarylife` GCP project.
4. **Service account** `vagarylife@vagarylife.iam.gserviceaccount.com` granted Admin in Play Console → Users & permissions.
5. **Play Developer API ToS** accepted (one-time banner).

## Standard release flow

```bash
# 0. Bump versionCode + versionName in app/build.gradle.kts
#    (and add metadata/android/en-US/changelogs/<new-versionCode>.txt)

# 1. Run engine tests
make test

# 2. Upload to internal testing track
make release-internal

# 3. Smoke-test on your own device (install via Play app internal track invite)
make status

# 4. Promote up the ladder
make promote-alpha   # closed testing
make promote-beta    # open testing
make promote-prod PCT=0.05   # 5% staged production rollout

# 5. Monitor; if bad, halt. If good, ramp.
make halt                          # stop the rollout
make rollout PCT=0.2               # widen to 20%
make rollout PCT=1.0               # full rollout (transitions to "completed")
```

## Listing / metadata changes

Edit files under `metadata/android/en-US/` and push:

```bash
make sync-listing              # all locales, includes images
make sync-listing-text         # faster; skip image re-upload
make sync-listing LANG=en-US   # single locale
```

The filesystem is the source of truth. Every sync replaces Play-side content.

## Observability

```bash
make status           # table of all tracks
make version-codes    # compact per-track version map
make reviews          # latest user reviews
```

## CI — tag-push auto-deploy

Push a tag matching `v*` → `.github/workflows/release.yml` builds a signed AAB and uploads it to the **internal** track automatically. Promotion to alpha/beta/production is manual (use the `make promote-*` targets). The CI never auto-publishes to production.

```bash
git tag v1.1.0
git push origin v1.1.0
# → internal testing build appears in Play Console within ~3 min
```

## Things you still must do in the browser (one-time per app)

Google does not expose these to the API:

- Create the app for the first time (for a brand-new package name)
- Complete the Content Rating questionnaire
- Complete the Data Safety declaration
- Complete the Target Audience + Content declaration
- Accept Play Console policy updates when Google rolls them out

After the first-time setup, everything else — release lifecycle, listing, pricing, in-app products, reviews — is fully automated from here.

## Emergency rollback

Play does not have a true "rollback" button. The workflow is:

1. `make halt TRACK=production` — freeze current rollout immediately.
2. Re-release a *higher versionCode* built from the last-known-good commit. Play refuses lower versionCodes.
3. Upload + promote that new build up the ladder.

## Troubleshooting

- **401 / forbidden**: SA not yet granted in Play Console, or API not enabled in GCP.
- **Package not found**: Play Console app hasn't been created for that package name yet (see "browser steps" above).
- **versionCode already used**: bump it in `app/build.gradle.kts`. Play treats every versionCode as immutable once uploaded to ANY track.
- **"ToS not accepted"**: open Play Console once, accept the API ToS banner.
