#!/usr/bin/env bash
# watch-build.sh — Monitor the latest GitHub Actions run.
# On failure: extract logs, ask Claude to fix, push, then re-watch.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

# ── Helpers ──────────────────────────────────────────────────────────────────

get_latest_run_id() {
    gh run list --limit 1 --json databaseId --jq '.[0].databaseId'
}

get_version() {
    grep -E 'versionName\s*=' app/build.gradle.kts \
        | grep -oE '"[^"]+"' | tr -d '"' || echo "unknown"
}

# ── Main loop ─────────────────────────────────────────────────────────────────

MAX_ITERATIONS=10
iteration=0

while true; do
    iteration=$((iteration + 1))
    if [ "$iteration" -gt "$MAX_ITERATIONS" ]; then
        echo "❌  Reached maximum fix attempts ($MAX_ITERATIONS). Stopping."
        exit 1
    fi

    RUN_ID="$(get_latest_run_id)"
    echo ""
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo "  Watching run #${RUN_ID}  (attempt ${iteration}/${MAX_ITERATIONS})"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

    # Watch until the run finishes (gh run watch exits when done)
    gh run watch "$RUN_ID" --exit-status 2>/dev/null
    EXIT_CODE=$?

    if [ "$EXIT_CODE" -eq 0 ]; then
        echo ""
        echo "✅  Build passed ✓"
        exit 0
    fi

    # ── Build failed ────────────────────────────────────────────────────────

    echo ""
    echo "❌  Build failed — fetching failure logs…"
    echo ""

    FAILED_LOGS="$(gh run view "$RUN_ID" --log-failed 2>&1 || true)"
    echo "$FAILED_LOGS"

    VERSION="$(get_version)"
    COMMIT_MSG="fix: build errors [${VERSION}]"

    echo ""
    echo "🤖  Asking Claude to fix the errors…"
    echo ""

    PROMPT="The GitHub Actions build for the ShrineLauncher Android project just failed.
Here are the full failure logs:

\`\`\`
${FAILED_LOGS}
\`\`\`

Please:
1. Read every file mentioned in the errors above.
2. Fix ALL compilation errors and warnings that caused the build to fail.
3. Do not change any logic or behaviour — only fix what is broken.
4. After fixing, commit with the message: \"${COMMIT_MSG}\"
5. Push the commit.

Make sure the fix is complete and the build will pass on the next run."

    claude --print "$PROMPT"

    echo ""
    echo "⏳  Waiting 10s for GitHub to register the new push…"
    sleep 10

    # Loop continues — will pick up the new run on next iteration
done
