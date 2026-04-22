#!/bin/bash
# check-docs-design.sh
# Validates the canonical docs/design/ 9-file scaffold per design-system.md v1+.
#
# Exit codes:
#   0 — all checks pass (or no docs/design/ — skipped with notice)
#   1 — structural failure (missing canonical files)
#   2 — content failure (invalid tokens.json, stale history.md)
#
# Usage: bash scripts/check-docs-design.sh [--tier A|B|C] [--strict]
#   --tier A|B|C   Repo tier (affects which files are required). Default: detect from CLAUDE.md, else A.
#   --strict       Treat all findings as errors (exit 1 on any non-zero).

set -eo pipefail

TIER="${1:-A}"
STRICT=false
for arg in "$@"; do
  case "$arg" in
    --tier) shift; TIER="$1"; shift ;;
    --strict) STRICT=true ;;
  esac
done

REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
DESIGN_DIR="$REPO_ROOT/docs/design"
ERRORS=0
WARNINGS=0

fail() { echo "FAIL: $1"; ERRORS=$((ERRORS + 1)); }
warn() { echo "WARN: $1"; WARNINGS=$((WARNINGS + 1)); }
pass() { echo "PASS: $1"; }

if [ ! -d "$DESIGN_DIR" ]; then
  if [ "$TIER" = "C" ]; then
    echo "SKIP: docs/design/ not present (Tier C — not required)"
    exit 0
  fi
  fail "docs/design/ not present (required for Tier $TIER)"
  exit 1
fi

# 1. Canonical file presence (Tier A = all 9; Tier B = minimal 6; Tier C = skip)
CANONICAL_A=(README.md brand.md palette.md typography.md components.md voice.md tokens.json history.md references/stitch.md references/figma.md)
CANONICAL_B=(README.md palette.md typography.md components.md tokens.json history.md)

case "$TIER" in
  A) CANONICAL=("${CANONICAL_A[@]}") ;;
  B) CANONICAL=("${CANONICAL_B[@]}") ;;
  *) CANONICAL=() ;;
esac

for f in "${CANONICAL[@]}"; do
  if [ -f "$DESIGN_DIR/$f" ]; then
    pass "docs/design/$f present"
  else
    fail "docs/design/$f missing (required for Tier $TIER)"
  fi
done

# 2. tokens.json validation
if [ -f "$DESIGN_DIR/tokens.json" ]; then
  if python3 -c "import json,sys; json.load(open('$DESIGN_DIR/tokens.json'))" 2>/dev/null; then
    pass "tokens.json is valid JSON"
  else
    fail "tokens.json is not valid JSON"
  fi
fi

# 3. history.md format: must have at least one YYYY-MM-DD entry
if [ -f "$DESIGN_DIR/history.md" ]; then
  if grep -qE '^## 20[0-9]{2}-[0-9]{2}-[0-9]{2}' "$DESIGN_DIR/history.md"; then
    pass "history.md has dated entry"
  else
    warn "history.md has no dated '## YYYY-MM-DD' entry"
  fi
fi

# 4. Drift detection: if palette/typography/components/tokens.json changed in HEAD
#    but history.md untouched, flag.
if git rev-parse HEAD >/dev/null 2>&1; then
  CHANGED_IN_HEAD=$(git diff-tree --no-commit-id --name-only -r HEAD 2>/dev/null || true)
  if echo "$CHANGED_IN_HEAD" | grep -qE 'docs/design/(palette|typography|components|tokens\.json|brand|voice)'; then
    if ! echo "$CHANGED_IN_HEAD" | grep -q 'docs/design/history\.md'; then
      warn "design artifacts changed in HEAD but history.md not updated — add entry documenting the change"
    fi
  fi
fi

echo ""
echo "Summary: $ERRORS errors, $WARNINGS warnings."

if $STRICT && [ "$WARNINGS" -gt 0 ]; then
  exit 1
fi

if [ "$ERRORS" -gt 0 ]; then
  exit 1
fi

exit 0
