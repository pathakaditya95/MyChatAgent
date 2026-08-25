#!/usr/bin/env bash
#
# Tells you whether a candidate App Secret actually belongs to your Meta app,
# without restarting anything.
#
# It asks Meta to validate an appsecret_proof: an HMAC-SHA256 of your access
# token keyed with the candidate secret. Meta recomputes it with the real secret,
# so only the correct value is accepted. Nothing is written and nothing changes.
#
#   ./scripts/check-app-secret.sh
#
# The secret is read from a hidden prompt, so it never lands in shell history.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

if [[ ! -f .env ]]; then
	echo "error: .env not found in $REPO_ROOT" >&2
	exit 1
fi

set -a && source .env && set +a

: "${META_ACCESS_TOKEN:?META_ACCESS_TOKEN is not set in .env}"
: "${META_GRAPH_VERSION:=v21.0}"

read -r -s -p "Paste the candidate App Secret (input hidden): " CANDIDATE
echo

if [[ -z "$CANDIDATE" ]]; then
	echo "No value entered." >&2
	exit 1
fi

echo
echo "Shape check:"
printf '  length %d ' "${#CANDIDATE}"
[[ ${#CANDIDATE} -eq 32 ]] && echo "(expected 32) OK" || echo "(expected 32) <- WRONG LENGTH"
printf '  characters '
if [[ "$CANDIDATE" =~ ^[0-9a-f]+$ ]]; then
	echo "all lowercase hex OK"
else
	echo "<- NOT hex. An App Secret is only 0-9 and a-f."
	echo "     If this has capitals or symbols it is probably your account password;"
	echo "     the dashboard asks for that only to reveal the secret."
fi

PROOF=$(printf '%s' "$META_ACCESS_TOKEN" | openssl dgst -sha256 -hmac "$CANDIDATE" | awk '{print $NF}')
RESPONSE=$(curl -s -G "https://graph.facebook.com/${META_GRAPH_VERSION}/me" \
	--data-urlencode "fields=id,name" \
	--data-urlencode "access_token=${META_ACCESS_TOKEN}" \
	--data-urlencode "appsecret_proof=${PROOF}")

echo
case "$RESPONSE" in
	*'"id"'*)
		echo "RESULT: VALID — this secret belongs to the app that issued your access token."
		echo "        Put it in .env as META_APP_SECRET and restart the app."
		;;
	*'Invalid appsecret_proof'*)
		echo "RESULT: WRONG — Meta rejected it. This is not the secret for this app."
		;;
	*)
		echo "RESULT: inconclusive. Raw response:"
		echo "  ${RESPONSE:0:300}"
		;;
esac
