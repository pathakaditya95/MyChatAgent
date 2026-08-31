#!/usr/bin/env bash
#
# Dumps distinct captured webhook payloads out of Postgres and into
# src/test/resources/fixtures/, grouped by event_type.
#
# Phase 5 tooling: run this after generating real interactions from a tester
# account, so Phase 6's normalizer can be written against genuine payloads
# rather than guesses.
#
#   ./scripts/dump-fixtures.sh            # up to 3 samples per event type
#   ./scripts/dump-fixtures.sh 5          # up to 5 samples per event type
#
# Existing fixture files are left alone unless --force is passed, so a re-run
# cannot quietly destroy a capture that took real effort to produce.

set -euo pipefail

PER_TYPE="${1:-3}"
FORCE="${FORCE:-0}"
[[ "${2:-}" == "--force" || "${1:-}" == "--force" ]] && FORCE=1
[[ "${1:-}" == "--force" ]] && PER_TYPE=3

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_DIR="$REPO_ROOT/src/test/resources/fixtures"
CONTAINER="${PG_CONTAINER:-metaautoreply-postgres}"
DB_USER="${DB_USER:-meta}"
DB_NAME="${DB_NAME:-metaautoreply}"

psql_q() {
	# stdin is redirected from /dev/null deliberately. This function is called from
	# inside `while read` loops that are fed by process substitution, and a docker
	# exec that inherits stdin will swallow the loop's remaining input — silently
	# processing only the first row. Do not add -i here.
	docker exec "$CONTAINER" psql -U "$DB_USER" -d "$DB_NAME" -tAc "$1" < /dev/null
}

if ! docker inspect "$CONTAINER" >/dev/null 2>&1; then
	echo "error: container '$CONTAINER' not found. Start it with: docker compose up -d postgres" >&2
	exit 1
fi

mkdir -p "$OUT_DIR"

TOTAL=$(psql_q "select count(*) from inbound_event;")
if [[ "$TOTAL" == "0" ]]; then
	echo "No events captured yet." >&2
	echo "Start the tunnel, point the Meta webhook at it, and produce some interactions first." >&2
	exit 1
fi

echo "Captured events: $TOTAL"
echo "Writing up to $PER_TYPE sample(s) per event type to:"
echo "  $OUT_DIR"
echo

written=0
skipped=0

while IFS='|' read -r event_type platform; do
	[[ -z "$event_type" ]] && continue

	# Distinct payloads only: redeliveries and repeated interactions of the same
	# shape add nothing to the fixture set.
	n=0
	while IFS= read -r payload; do
		[[ -z "$payload" ]] && continue
		n=$((n + 1))

		slug=$(echo "${platform}-${event_type}" | tr '[:upper:]' '[:lower:]' | tr -cs 'a-z0-9' '-')
		slug="${slug%-}"
		file="$OUT_DIR/${slug}-${n}.json"

		if [[ -f "$file" && "$FORCE" != "1" ]]; then
			echo "  skip   ${file#"$REPO_ROOT"/} (exists; FORCE=1 to overwrite)"
			skipped=$((skipped + 1))
			continue
		fi

		# Pretty-print so the fixture is readable and diffable.
		if printf '%s' "$payload" | python3 -m json.tool --indent 2 > "$file.tmp" 2>/dev/null; then
			mv "$file.tmp" "$file"
		else
			# Unparseable payloads are still evidence — keep them verbatim.
			printf '%s\n' "$payload" > "$file"
			rm -f "$file.tmp"
		fi
		echo "  write  ${file#"$REPO_ROOT"/}"
		written=$((written + 1))
	done < <(psql_q "select distinct payload::text from inbound_event
	                 where event_type = '$event_type' and platform = '$platform'
	                 limit $PER_TYPE;")

done < <(psql_q "select distinct event_type, platform from inbound_event order by 1, 2;")

echo
echo "Done: $written written, $skipped skipped."
echo
echo "Event types captured so far:"
psql_q "select '  ' || platform || ' ' || event_type || '  x' || count(*)
        from inbound_event group by platform, event_type order by 1;"
echo
echo "Next: record the JSON paths for comment id, commenter id, username, text,"
echo "parent media id and 'verb' in NOTES.md, then Phase 6 can begin."
