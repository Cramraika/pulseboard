#!/usr/bin/env python3
"""
google-play-publisher.py — Google Play Developer Publisher API helper.

Wraps the official Android Publisher REST API (androidpublisher v3) with a CLI
that covers the common-case release flow: list apps, upload APK/AAB, create a
track release, update listing copy, fetch reviews.

Auth: Service Account JSON (generated in Google Cloud Console, granted access
in Play Console under Users & Permissions). Path to the JSON lives in env var
`GOOGLE_PLAY_SERVICE_ACCOUNT_JSON` (default: ~/.config/google-play/sa.json).

Install deps (one time):
    python3 -m pip install --user google-auth google-api-python-client

Common usage:
    ./google-play-publisher.py list
    ./google-play-publisher.py upload --package com.cramraika.pulseboard --apk ./app-release.apk --track internal
    ./google-play-publisher.py release --package com.cramraika.pulseboard --version-code 1 --track production --release-name "v1.0" --notes-en "First public release"
    ./google-play-publisher.py reviews --package com.cramraika.pulseboard
    ./google-play-publisher.py listing --package com.cramraika.pulseboard --lang en-US

No MCP server exists for Play yet; if one is ever built it would wrap this same
API. For now, invoke directly from Claude Code via Bash.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path
from typing import Any, Optional

try:
    from google.oauth2 import service_account
    from googleapiclient.discovery import build
    from googleapiclient.errors import HttpError
    from googleapiclient.http import MediaFileUpload
except ImportError as e:
    sys.stderr.write(
        f"Missing dependency: {e.name}. Install with:\n"
        "    python3 -m pip install --user google-auth google-api-python-client\n"
    )
    sys.exit(1)


SCOPES = ["https://www.googleapis.com/auth/androidpublisher"]
DEFAULT_SA_PATH = Path.home() / ".config" / "google-play" / "sa.json"

# Admin SDK scopes — only requested when `members` command is used, and only
# valid for Workspace-domain groups (NOT public googlegroups.com groups).
# Requires the SA to have domain-wide delegation enabled in admin.google.com
# and an impersonated admin email passed via --admin-email or $PLAY_ADMIN_EMAIL.
ADMIN_DIRECTORY_SCOPES = [
    "https://www.googleapis.com/auth/admin.directory.group.member",
    "https://www.googleapis.com/auth/admin.directory.group.readonly",
]


def build_service() -> Any:
    sa_path = Path(os.environ.get("GOOGLE_PLAY_SERVICE_ACCOUNT_JSON", DEFAULT_SA_PATH))
    if not sa_path.exists():
        sys.stderr.write(
            f"Service account JSON not found at {sa_path}.\n"
            "Set GOOGLE_PLAY_SERVICE_ACCOUNT_JSON or place sa.json at that path.\n"
        )
        sys.exit(2)
    creds = service_account.Credentials.from_service_account_file(
        str(sa_path), scopes=SCOPES
    )
    return build("androidpublisher", "v3", credentials=creds, cache_discovery=False)


def cmd_list(args: argparse.Namespace) -> None:
    # There is no /apps list endpoint; Play expects you to know your package.
    # We simulate by showing a helpful error; Play Console itself is the source of truth.
    print(
        "Play API has no 'list apps' endpoint. Your packages are defined in Play Console.\n"
        "Common discovery: `--package com.cramraika.<product>`"
    )


def _edit_begin(service: Any, package: str) -> str:
    edit = service.edits().insert(packageName=package, body={}).execute()
    return edit["id"]


def _edit_commit(service: Any, package: str, edit_id: str) -> None:
    service.edits().commit(packageName=package, editId=edit_id).execute()


def cmd_upload(args: argparse.Namespace) -> None:
    service = build_service()
    edit_id = _edit_begin(service, args.package)
    try:
        file_path = Path(args.apk or args.aab).resolve()
        is_aab = file_path.suffix == ".aab"
        media = MediaFileUpload(
            str(file_path),
            mimetype="application/octet-stream"
            if is_aab
            else "application/vnd.android.package-archive",
        )
        if is_aab:
            result = (
                service.edits()
                .bundles()
                .upload(packageName=args.package, editId=edit_id, media_body=media)
                .execute()
            )
        else:
            result = (
                service.edits()
                .apks()
                .upload(packageName=args.package, editId=edit_id, media_body=media)
                .execute()
            )
        version_code = result["versionCode"]
        # Attach to track. Upload always creates a DRAFT release — safe by
        # default, works on brand-new "draft apps" (where Play rejects any
        # non-draft first release), and matches fastlane supply's two-phase
        # model. To roll out, run `release --status completed` afterwards.
        service.edits().tracks().update(
            packageName=args.package,
            editId=edit_id,
            track=args.track,
            body={
                "track": args.track,
                "releases": [
                    {
                        "name": args.release_name or f"v{version_code}",
                        "status": args.status,
                        "versionCodes": [str(version_code)],
                    }
                ],
            },
        ).execute()
        _edit_commit(service, args.package, edit_id)
        print(f"Uploaded versionCode={version_code} to {args.package}:{args.track} (status={args.status})")
    except HttpError as e:
        sys.stderr.write(f"Upload failed: {e}\n")
        sys.exit(3)


def cmd_release(args: argparse.Namespace) -> None:
    service = build_service()
    edit_id = _edit_begin(service, args.package)
    try:
        release_notes = []
        if args.notes_en:
            release_notes.append({"language": "en-US", "text": args.notes_en})
        body = {
            "track": args.track,
            "releases": [
                {
                    "name": args.release_name,
                    "status": args.status,
                    "versionCodes": [str(args.version_code)],
                    "releaseNotes": release_notes or None,
                }
            ],
        }
        service.edits().tracks().update(
            packageName=args.package, editId=edit_id, track=args.track, body=body
        ).execute()
        _edit_commit(service, args.package, edit_id)
        print(f"Released versionCode={args.version_code} to {args.track}")
    except HttpError as e:
        sys.stderr.write(f"Release failed: {e}\n")
        sys.exit(3)


def cmd_listing(args: argparse.Namespace) -> None:
    service = build_service()
    edit_id = _edit_begin(service, args.package)
    try:
        if args.update:
            body = json.loads(Path(args.update).read_text())
            service.edits().listings().update(
                packageName=args.package,
                editId=edit_id,
                language=args.lang,
                body=body,
            ).execute()
            _edit_commit(service, args.package, edit_id)
            print(f"Listing updated: {args.package}:{args.lang}")
            return
        result = (
            service.edits()
            .listings()
            .get(packageName=args.package, editId=edit_id, language=args.lang)
            .execute()
        )
        # Read-only: no commit needed; drop the edit by not committing.
        print(json.dumps(result, indent=2))
    except HttpError as e:
        sys.stderr.write(f"Listing op failed: {e}\n")
        sys.exit(3)


def cmd_reviews(args: argparse.Namespace) -> None:
    service = build_service()
    try:
        params = {"packageName": args.package}
        if args.translationLanguage:
            params["translationLanguage"] = args.translationLanguage
        result = service.reviews().list(**params).execute()
        print(json.dumps(result, indent=2))
    except HttpError as e:
        sys.stderr.write(f"Reviews fetch failed: {e}\n")
        sys.exit(3)


TRACK_NAMES = ("internal", "alpha", "beta", "production")


def _get_track(service: Any, package: str, edit_id: str, track: str) -> dict:
    return service.edits().tracks().get(
        packageName=package, editId=edit_id, track=track
    ).execute()


def cmd_status(args: argparse.Namespace) -> None:
    service = build_service()
    edit_id = _edit_begin(service, args.package)
    try:
        rows = []
        for track in TRACK_NAMES:
            try:
                t = _get_track(service, args.package, edit_id, track)
                for rel in t.get("releases", []) or []:
                    rows.append({
                        "track": track,
                        "name": rel.get("name"),
                        "status": rel.get("status"),
                        "versionCodes": rel.get("versionCodes", []),
                        "userFraction": rel.get("userFraction"),
                    })
            except HttpError:
                continue
        if args.json:
            print(json.dumps(rows, indent=2))
            return
        if not rows:
            print(f"No releases found for {args.package}")
            return
        print(f"{'TRACK':<12}{'NAME':<22}{'STATUS':<14}{'VCODES':<16}{'ROLLOUT'}")
        for r in rows:
            frac = r["userFraction"]
            rollout = f"{float(frac)*100:.1f}%" if frac is not None else "-"
            print(
                f"{r['track']:<12}{(r['name'] or ''):<22}{(r['status'] or ''):<14}"
                f"{','.join(r['versionCodes']):<16}{rollout}"
            )
    except HttpError as e:
        sys.stderr.write(f"Status fetch failed: {e}\n")
        sys.exit(3)


def cmd_version_codes(args: argparse.Namespace) -> None:
    service = build_service()
    edit_id = _edit_begin(service, args.package)
    try:
        for track in TRACK_NAMES:
            try:
                t = _get_track(service, args.package, edit_id, track)
                codes: list[str] = []
                for rel in t.get("releases", []) or []:
                    codes.extend(rel.get("versionCodes", []))
                print(f"{track}: {','.join(codes) if codes else '-'}")
            except HttpError:
                print(f"{track}: -")
    except HttpError as e:
        sys.stderr.write(f"version-codes failed: {e}\n")
        sys.exit(3)


def _mutate_current_release(
    service: Any,
    package: str,
    track: str,
    mutator,
) -> None:
    edit_id = _edit_begin(service, package)
    t = _get_track(service, package, edit_id, track)
    releases = t.get("releases") or []
    if not releases:
        sys.stderr.write(f"No active release on track '{track}' for {package}\n")
        sys.exit(4)
    mutator(releases[0])
    service.edits().tracks().update(
        packageName=package,
        editId=edit_id,
        track=track,
        body={"track": track, "releases": releases},
    ).execute()
    _edit_commit(service, package, edit_id)


def cmd_rollout(args: argparse.Namespace) -> None:
    frac = float(args.fraction)
    if not 0.0 < frac <= 1.0:
        sys.stderr.write("--fraction must be in (0.0, 1.0]\n")
        sys.exit(2)

    def _mut(rel: dict) -> None:
        if frac >= 1.0:
            rel["status"] = "completed"
            rel.pop("userFraction", None)
        else:
            rel["status"] = "inProgress"
            rel["userFraction"] = frac

    service = build_service()
    try:
        _mutate_current_release(service, args.package, args.track, _mut)
        print(f"Rollout set: {args.package}:{args.track} → {frac*100:.1f}%")
    except HttpError as e:
        sys.stderr.write(f"Rollout failed: {e}\n")
        sys.exit(3)


def cmd_halt(args: argparse.Namespace) -> None:
    def _mut(rel: dict) -> None:
        rel["status"] = "halted"

    service = build_service()
    try:
        _mutate_current_release(service, args.package, args.track, _mut)
        print(f"Rollout HALTED: {args.package}:{args.track}")
    except HttpError as e:
        sys.stderr.write(f"Halt failed: {e}\n")
        sys.exit(3)


def cmd_resume(args: argparse.Namespace) -> None:
    frac = float(args.fraction)
    if not 0.0 < frac <= 1.0:
        sys.stderr.write("--fraction must be in (0.0, 1.0]\n")
        sys.exit(2)

    def _mut(rel: dict) -> None:
        if frac >= 1.0:
            rel["status"] = "completed"
            rel.pop("userFraction", None)
        else:
            rel["status"] = "inProgress"
            rel["userFraction"] = frac

    service = build_service()
    try:
        _mutate_current_release(service, args.package, args.track, _mut)
        print(f"Resumed {args.package}:{args.track} @ {frac*100:.1f}%")
    except HttpError as e:
        sys.stderr.write(f"Resume failed: {e}\n")
        sys.exit(3)


# Metadata directory layout (compatible with fastlane supply):
#   metadata/android/
#     <locale>/           e.g. en-US, hi-IN
#       title.txt                     (max 30 chars)
#       short_description.txt         (max 80 chars)
#       full_description.txt          (max 4000 chars)
#       video.txt                     (optional YouTube URL)
#       changelogs/<versionCode>.txt  (max 500 chars; used by `release --notes-file`)
#       images/
#         icon.png                    512x512
#         featureGraphic.png          1024x500
#         phoneScreenshots/*.png      2–8 images
#         sevenInchScreenshots/*.png
#         tenInchScreenshots/*.png
_IMAGE_TYPES = {
    "icon": "icon",
    "featureGraphic": "featureGraphic",
    "phoneScreenshots": "phoneScreenshots",
    "sevenInchScreenshots": "sevenInchScreenshots",
    "tenInchScreenshots": "tenInchScreenshots",
}


def _read_if_exists(path: Path) -> Optional[str]:
    return path.read_text().strip() if path.exists() else None


def _build_directory_service(admin_email: str) -> Any:
    """Build an Admin SDK Directory service impersonating the given admin.

    Prerequisites (all one-time per Workspace domain):
      1. Admin SDK API enabled in GCP project (already done 2026-04-22 for vagarylife)
      2. Service account has domain-wide delegation in admin.google.com
         (Security → API Controls → Domain-wide Delegation → Add client with
         the SA's oauth_client_id + scopes above)
      3. `admin_email` is a super admin of the Workspace domain whose identity
         the SA impersonates
    """
    sa_path = Path(os.environ.get("GOOGLE_PLAY_SERVICE_ACCOUNT_JSON", DEFAULT_SA_PATH))
    creds = service_account.Credentials.from_service_account_file(
        str(sa_path), scopes=ADMIN_DIRECTORY_SCOPES,
    ).with_subject(admin_email)
    return build("admin", "directory_v1", credentials=creds, cache_discovery=False)


def cmd_members(args: argparse.Namespace) -> None:
    """Manage members of a Workspace-domain Google Group via Admin SDK.

    Requires domain-wide delegation + an admin email to impersonate. Does NOT
    work for public googlegroups.com groups — those need browser-only
    management at groups.google.com.

    Usage:
        members --group testers@vagarylife.com --admin chinmay@vagarylife.com --list
        members --group testers@vagarylife.com --admin chinmay@vagarylife.com --add foo@gmail.com
        members --group testers@vagarylife.com --admin chinmay@vagarylife.com --remove foo@gmail.com
    """
    admin = args.admin or os.environ.get("PLAY_ADMIN_EMAIL")
    if not admin:
        sys.stderr.write("--admin <admin@workspace.domain> (or PLAY_ADMIN_EMAIL env) required.\n")
        sys.exit(2)
    try:
        svc = _build_directory_service(admin)
    except Exception as e:
        sys.stderr.write(
            f"Admin SDK auth failed: {e}\n"
            "Check: (1) Admin SDK API enabled, (2) Domain-wide delegation configured, "
            "(3) --admin is a Workspace super admin.\n"
        )
        sys.exit(3)

    try:
        if args.list:
            resp = svc.members().list(groupKey=args.group, maxResults=500).execute()
            members = resp.get("members", [])
            if args.json:
                print(json.dumps(members, indent=2))
                return
            if not members:
                print(f"(no members in {args.group})")
                return
            for m in members:
                print(f"  {m.get('email')}  ({m.get('role', 'MEMBER')}, {m.get('type', 'USER')})")
            return
        if args.add:
            body = {"email": args.add, "role": args.role or "MEMBER"}
            r = svc.members().insert(groupKey=args.group, body=body).execute()
            print(f"added {r.get('email')} to {args.group} as {r.get('role')}")
            return
        if args.remove:
            svc.members().delete(groupKey=args.group, memberKey=args.remove).execute()
            print(f"removed {args.remove} from {args.group}")
            return
        sys.stderr.write("Pass --list, --add <email>, or --remove <email>\n")
        sys.exit(2)
    except HttpError as e:
        sys.stderr.write(f"members op failed: {e}\n")
        sys.exit(3)


def cmd_testers(args: argparse.Namespace) -> None:
    """Get or set the testers list (Google Groups only) attached to a track.

    Play Dev API `edits.testers` only accepts `googleGroups` (email addresses
    of Google Groups) and `autoEnrolledAndroidGroups` (org-managed). Individual
    tester emails are not exposed via API — use Play Console UI for those.

    Usage:
        testers --package <pkg> --track internal --get
        testers --package <pkg> --track internal --set-groups list@group.example.com,another@group.example.com
        testers --package <pkg> --track internal --clear
    """
    service = build_service()
    edit_id = _edit_begin(service, args.package)
    try:
        if args.get:
            r = service.edits().testers().get(
                packageName=args.package, editId=edit_id, track=args.track,
            ).execute()
            print(json.dumps(r or {}, indent=2))
            return
        body: dict = {}
        if args.clear:
            body = {"googleGroups": []}
        elif args.set_groups:
            groups = [g.strip() for g in args.set_groups.split(",") if g.strip()]
            body = {"googleGroups": groups}
        else:
            sys.stderr.write("Pass --get, --clear, or --set-groups <csv>\n")
            sys.exit(2)
        r = service.edits().testers().update(
            packageName=args.package, editId=edit_id, track=args.track, body=body,
        ).execute()
        _edit_commit(service, args.package, edit_id)
        print(f"testers {args.track} updated → googleGroups={body.get('googleGroups', [])}")
    except HttpError as e:
        sys.stderr.write(f"testers op failed: {e}\n")
        sys.exit(3)


def cmd_details(args: argparse.Namespace) -> None:
    """Read or update app details (contact website / email / phone, default language).

    Play Dev API v3 `edits.details` resource covers the Store presence →
    Contact details + default language. Privacy policy URL is NOT exposed
    here (Play Console UI only — see docs/play/PLAY_CHECKLIST.md §2.1).
    """
    service = build_service()
    edit_id = _edit_begin(service, args.package)
    try:
        if args.get:
            d = service.edits().details().get(
                packageName=args.package, editId=edit_id
            ).execute()
            print(json.dumps(d, indent=2))
            return
        body: dict = {}
        if args.website is not None:
            body["contactWebsite"] = args.website
        if args.email is not None:
            body["contactEmail"] = args.email
        if args.phone is not None:
            body["contactPhone"] = args.phone
        if args.default_language is not None:
            body["defaultLanguage"] = args.default_language
        if not body:
            sys.stderr.write("Nothing to update — pass --website / --email / --phone / --default-language or --get\n")
            sys.exit(2)
        service.edits().details().update(
            packageName=args.package, editId=edit_id, body=body
        ).execute()
        _edit_commit(service, args.package, edit_id)
        print(f"details updated: {', '.join(body.keys())}")
    except HttpError as e:
        sys.stderr.write(f"details op failed: {e}\n")
        sys.exit(3)


def cmd_sync_listing(args: argparse.Namespace) -> None:
    base = Path(args.dir).resolve()
    if not base.exists():
        sys.stderr.write(f"metadata dir not found: {base}\n")
        sys.exit(2)
    locales = [args.lang] if args.lang else [
        p.name for p in base.iterdir() if p.is_dir()
    ]
    if not locales:
        sys.stderr.write(f"no locale subdirs under {base}\n")
        sys.exit(2)

    service = build_service()
    edit_id = _edit_begin(service, args.package)
    try:
        for loc in locales:
            ldir = base / loc
            if not ldir.is_dir():
                print(f"skip {loc}: no dir at {ldir}")
                continue
            body = {}
            for field, fname in [
                ("title", "title.txt"),
                ("shortDescription", "short_description.txt"),
                ("fullDescription", "full_description.txt"),
                ("video", "video.txt"),
            ]:
                v = _read_if_exists(ldir / fname)
                if v is not None:
                    body[field] = v
            if not body:
                print(f"skip {loc}: no text fields found")
                continue
            service.edits().listings().update(
                packageName=args.package,
                editId=edit_id,
                language=loc,
                body=body,
            ).execute()
            print(f"listing synced: {args.package}:{loc} ({', '.join(body.keys())})")

            if args.skip_images:
                continue
            images_dir = ldir / "images"
            if not images_dir.is_dir():
                continue
            for subname, api_type in _IMAGE_TYPES.items():
                target = images_dir / subname
                if api_type in ("icon", "featureGraphic"):
                    f = images_dir / f"{subname}.png"
                    if not f.exists():
                        continue
                    service.edits().images().deleteall(
                        packageName=args.package,
                        editId=edit_id,
                        language=loc,
                        imageType=api_type,
                    ).execute()
                    media = MediaFileUpload(str(f), mimetype="image/png")
                    service.edits().images().upload(
                        packageName=args.package,
                        editId=edit_id,
                        language=loc,
                        imageType=api_type,
                        media_body=media,
                    ).execute()
                    print(f"  image: {api_type} ← {f.name}")
                else:
                    if not target.is_dir():
                        continue
                    service.edits().images().deleteall(
                        packageName=args.package,
                        editId=edit_id,
                        language=loc,
                        imageType=api_type,
                    ).execute()
                    for img in sorted(target.glob("*.png")):
                        media = MediaFileUpload(str(img), mimetype="image/png")
                        service.edits().images().upload(
                            packageName=args.package,
                            editId=edit_id,
                            language=loc,
                            imageType=api_type,
                            media_body=media,
                        ).execute()
                        print(f"  image: {api_type} ← {img.name}")
        _edit_commit(service, args.package, edit_id)
        print("sync-listing committed.")
    except HttpError as e:
        sys.stderr.write(f"sync-listing failed: {e}\n")
        sys.exit(3)


def main() -> None:
    p = argparse.ArgumentParser(description="Google Play Publisher API helper")
    sub = p.add_subparsers(dest="command", required=True)

    sub.add_parser("list", help="(Hint) Play API has no list-apps endpoint")

    up = sub.add_parser("upload", help="Upload APK or AAB to a track as a draft")
    up.add_argument("--package", required=True)
    up.add_argument("--apk")
    up.add_argument("--aab")
    up.add_argument("--track", default="internal",
                    choices=["internal", "alpha", "beta", "production"])
    up.add_argument("--release-name", default=None)
    up.add_argument("--status", default="draft",
                    choices=["draft", "completed", "inProgress", "halted"],
                    help="Default draft (safe); use `release` to roll out to testers/users")

    rel = sub.add_parser("release", help="Promote or create a release")
    rel.add_argument("--package", required=True)
    rel.add_argument("--version-code", required=True, type=int)
    rel.add_argument("--track", required=True,
                     choices=["internal", "alpha", "beta", "production"])
    rel.add_argument("--release-name", required=True)
    rel.add_argument("--status", default="completed",
                     choices=["draft", "inProgress", "halted", "completed"])
    rel.add_argument("--notes-en", default=None)

    lst = sub.add_parser("listing", help="Get or update store listing for a language")
    lst.add_argument("--package", required=True)
    lst.add_argument("--lang", default="en-US")
    lst.add_argument("--update",
                     help="Path to a JSON file with listing body (full replace)")

    rv = sub.add_parser("reviews", help="Fetch latest reviews")
    rv.add_argument("--package", required=True)
    rv.add_argument("--translationLanguage", default=None)

    st = sub.add_parser("status", help="Show releases across all tracks")
    st.add_argument("--package", required=True)
    st.add_argument("--json", action="store_true", help="Emit machine-readable JSON")

    vc = sub.add_parser("version-codes", help="Print current versionCode per track")
    vc.add_argument("--package", required=True)

    ro = sub.add_parser("rollout", help="Set staged rollout user fraction on current release")
    ro.add_argument("--package", required=True)
    ro.add_argument("--track", required=True, choices=list(TRACK_NAMES))
    ro.add_argument("--fraction", required=True,
                    help="0.0 < f <= 1.0 (e.g. 0.05, 0.1, 0.5, 1.0)")

    ha = sub.add_parser("halt", help="Halt the current rollout on a track")
    ha.add_argument("--package", required=True)
    ha.add_argument("--track", required=True, choices=list(TRACK_NAMES))

    rs = sub.add_parser("resume", help="Resume a halted rollout on a track")
    rs.add_argument("--package", required=True)
    rs.add_argument("--track", required=True, choices=list(TRACK_NAMES))
    rs.add_argument("--fraction", required=True,
                    help="0.0 < f <= 1.0 — what to resume at")

    mb = sub.add_parser("members",
                        help="Manage Google Group membership via Admin SDK (Workspace groups only)")
    mb.add_argument("--group", required=True,
                    help="Workspace-domain group email, e.g. testers@vagarylife.com")
    mb.add_argument("--admin", default=None,
                    help="Workspace super admin to impersonate (or set PLAY_ADMIN_EMAIL env)")
    mb.add_argument("--list", action="store_true")
    mb.add_argument("--add", default=None, metavar="EMAIL")
    mb.add_argument("--remove", default=None, metavar="EMAIL")
    mb.add_argument("--role", default="MEMBER", choices=["MEMBER", "MANAGER", "OWNER"])
    mb.add_argument("--json", action="store_true")

    ts = sub.add_parser("testers",
                        help="Get/set Google-Group tester lists on a track (individual emails: Play Console UI only)")
    ts.add_argument("--package", required=True)
    ts.add_argument("--track", required=True, choices=list(TRACK_NAMES))
    ts.add_argument("--get", action="store_true")
    ts.add_argument("--set-groups", default=None,
                    help="Comma-separated Google Group emails (e.g. testers@acme.com)")
    ts.add_argument("--clear", action="store_true",
                    help="Remove all Google Groups from this track")

    dt = sub.add_parser("details",
                        help="Read/update contact website, email, phone, default language")
    dt.add_argument("--package", required=True)
    dt.add_argument("--get", action="store_true", help="Read current details as JSON")
    dt.add_argument("--website", default=None, help="Contact website URL")
    dt.add_argument("--email", default=None, help="Contact email (public on store)")
    dt.add_argument("--phone", default=None, help="Contact phone (public; optional)")
    dt.add_argument("--default-language", default=None,
                    help="Default listing locale, e.g. en-US")

    sl = sub.add_parser("sync-listing",
                        help="Push metadata/<locale>/{title,short_description,full_description,video,images/*}")
    sl.add_argument("--package", required=True)
    sl.add_argument("--dir", default="metadata/android",
                    help="Base metadata dir (default: metadata/android relative to CWD)")
    sl.add_argument("--lang", default=None,
                    help="Sync only this locale (default: every subdir of --dir)")
    sl.add_argument("--skip-images", action="store_true",
                    help="Only sync text fields; skip image upload/replace")

    args = p.parse_args()
    cmd = args.command
    {
        "list": cmd_list,
        "upload": cmd_upload,
        "release": cmd_release,
        "listing": cmd_listing,
        "reviews": cmd_reviews,
        "status": cmd_status,
        "version-codes": cmd_version_codes,
        "rollout": cmd_rollout,
        "halt": cmd_halt,
        "resume": cmd_resume,
        "sync-listing": cmd_sync_listing,
        "details": cmd_details,
        "testers": cmd_testers,
        "members": cmd_members,
    }[cmd](args)


if __name__ == "__main__":
    main()
