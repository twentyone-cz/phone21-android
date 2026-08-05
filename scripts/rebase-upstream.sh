#!/usr/bin/env bash
# Rebase forku na nový upstream tag. Použití: ./scripts/rebase-upstream.sh 6.2.5
set -euo pipefail

NEW_TAG="${1:?použití: $0 <upstream-tag>  (např. 6.2.5)}"
UPSTREAM_URL=https://github.com/BelledonneCommunications/linphone-android.git

git config rerere.enabled true
git remote get-url upstream >/dev/null 2>&1 || git remote add upstream "$UPSTREAM_URL"
git fetch upstream --tags

# základ = poslední upstream tag dosažitelný z HEAD (naše tagy mají -21p)
OLD_TAG=$(git describe --tags --abbrev=0 --match '[0-9]*' --exclude '*-21p*' HEAD)
echo "==> rebase našich komitů z $OLD_TAG na $NEW_TAG"

BRANCH="rebase/$NEW_TAG"
git checkout -b "$BRANCH"
git rebase --onto "$NEW_TAG" "$OLD_TAG" || {
  echo
  echo "KONFLIKTY — řeš po tématech (viz FORK.md tabulka), pak:"
  echo "  git rebase --continue"
  exit 1
}

echo
echo "==> hotovo. Zkontroluj dotčené soubory proti FORK.md:"
git diff --stat "$NEW_TAG" | tail -20
echo
echo "Další kroky: build + smoke test, pak:"
echo "  git checkout main-21p && git reset --hard $BRANCH"
echo "  git tag $NEW_TAG-21p.1 && git push origin main-21p --tags"
