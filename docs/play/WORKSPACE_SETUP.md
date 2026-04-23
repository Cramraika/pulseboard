# Workspace Admin SDK Setup — for 100% API-driven tester management

Closes the last automation gap: adding/removing individual testers on Play's alpha/beta tracks without any browser click after the one-time setup below.

## Prerequisites

- `vagarylife.com` is a Google Workspace domain (you can log into [admin.google.com](https://admin.google.com) as a super admin)
- Admin SDK API enabled in GCP project `vagarylife` ✅ _(enabled 2026-04-22 via Service Usage API)_
- Service account: `vagarylife@vagarylife.iam.gserviceaccount.com`
- SA client ID: `114365868903997279327`

## One-time setup (15 min browser, once forever)

### Step 1 — Create a Workspace-domain Google Group
**Where**: [admin.google.com](https://admin.google.com) → Directory → Groups → **Create group**

Recommended:
- Group name: `Vagary Labs Testers`
- Group email: `testers@vagarylife.com`
- Access type: **Restricted** (invitees only; members can see everyone in group)
- Allow external members: **Yes** (so non-Workspace gmails like `chinu.ramraika@gmail.com` can be added)

Why not `vagarylabs-tester@googlegroups.com`? That's a public googlegroups.com group — Google does NOT expose membership management for those via any API. Only Workspace-domain groups (`*@<yourdomain>`) are manageable by Admin SDK.

### Step 2 — Grant Domain-Wide Delegation to the service account
**Where**: [admin.google.com](https://admin.google.com) → Security → Access and data control → **API controls** → Manage Domain Wide Delegation → **Add new**

Fill in:
- **Client ID**: `114365868903997279327` _(the `vagarylife@vagarylife.iam.gserviceaccount.com` SA's oauth client ID)_
- **OAuth scopes** (comma-separated):
  ```
  https://www.googleapis.com/auth/admin.directory.group.member,https://www.googleapis.com/auth/admin.directory.group.readonly
  ```

Click **Authorize**.

### Step 3 — Set your super admin email as PLAY_ADMIN_EMAIL
The SA impersonates a human admin's identity when it calls Admin SDK. Pick a super admin email in the `vagarylife.com` domain (e.g. `chinmay@vagarylife.com` or whatever your admin identity is) and export it:

```bash
# Add to ~/.zshrc or ~/.bashrc
export PLAY_ADMIN_EMAIL="chinmay@vagarylife.com"
```

### Step 4 — Swap Play tracks from the public group to the Workspace group

```bash
python3 ~/.claude/scripts/google-play-publisher.py testers \
  --package com.vagarylabs.pulseboard --track alpha \
  --set-groups testers@vagarylife.com
```

_(Removing the old public group is automatic because `--set-groups` replaces the full list.)_

## Verification

After Steps 1–3:

```bash
# List current members of the Workspace group
python3 ~/.claude/scripts/google-play-publisher.py members \
  --group testers@vagarylife.com --list
```

Should print current members (will be empty initially).

## Forever-after usage

```bash
# Add a tester — NO BROWSER
python3 ~/.claude/scripts/google-play-publisher.py members \
  --group testers@vagarylife.com \
  --add chinu.ramraika@gmail.com

# Remove a tester
python3 ~/.claude/scripts/google-play-publisher.py members \
  --group testers@vagarylife.com \
  --remove chinu.ramraika@gmail.com

# List members
python3 ~/.claude/scripts/google-play-publisher.py members \
  --group testers@vagarylife.com --list
```

These commands are zero-browser for every tester add/remove across every Vagary Labs app forever. The Workspace group is shared across all apps (just attach to each app's alpha/beta track via `make testers`), so one membership change propagates.

## What's still browser after this setup

Only the **one-time per-app setup** (see `docs/play/AUTOMATION_ENVELOPE.md`):
1. Create app in Play Console
2. Declarations (privacy, access, ads, content rating, target audience, data safety, etc.)
3. Category + tags
4. (Optional) First "Start rollout to Internal testing" click

Everything else is 100% CLI-driven. Every release on every track, every tester add/remove across every app.

## For a NEW Vagary Labs app

The bootstrap script's post-scaffold checklist becomes:

1. Create app in Play Console (1 min)
2. Transcribe DECLARATIONS.md → Play forms (25-30 min)
3. Paste privacy URL, pick category + tags (2 min)
4. `make testers TRACK=alpha ARGS="--set-groups testers@vagarylife.com"` (CLI, instant)
5. `make release-internal` then `make promote-alpha`
6. Testers auto-receive via shared Workspace group — **zero per-app tester management**

**Net per-new-app browser time: ~30 min** (declarations dominate; everything else is CLI).
