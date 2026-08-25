#!/usr/bin/env bash
#
# Exchanges the System User token in .env for a Page access token and writes it
# back to META_ACCESS_TOKEN.
#
# Why this is needed: publishing a comment reply requires a *Page* access token.
# A System User token carries the right permissions but is the wrong type, and
# Meta rejects it with:
#
#   (#3) Publishing comments through the API is only available for page access tokens
#
# A Page token derived from a never-expiring System User token also never expires,
# so this is a one-off step rather than something to schedule.
#
# PREREQUISITE: the Page must be assigned to the System User in Business Settings
# -> Users -> System Users -> (your user) -> Add Assets -> Pages. Without that,
# the System User has permissions but no Page to mint a token for, and this script
# will tell you so.
#
# The token is never printed; it goes straight into .env, which is gitignored.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

[[ -f .env ]] || { echo "error: .env not found in $REPO_ROOT" >&2; exit 1; }
set -a && source .env && set +a
: "${META_ACCESS_TOKEN:?META_ACCESS_TOKEN is not set in .env}"
: "${META_PAGE_ID:?META_PAGE_ID is not set in .env}"
: "${META_GRAPH_VERSION:=v21.0}"

python3 - "$META_GRAPH_VERSION" "$META_PAGE_ID" "$META_ACCESS_TOKEN" <<'PY'
import json, sys, urllib.parse, urllib.request, pathlib, re, shutil

ver, page, token = sys.argv[1], sys.argv[2], sys.argv[3]

def graph(path, **params):
    params["access_token"] = token
    url = f"https://graph.facebook.com/{ver}/{path}?" + urllib.parse.urlencode(params)
    try:
        return json.load(urllib.request.urlopen(url))
    except urllib.error.HTTPError as e:
        return json.load(e)

info = graph("debug_token", input_token=token)
data = info.get("data", {})
token_type = data.get("type")
print(f"current token type: {token_type}")

if token_type == "PAGE":
    print("Already a Page token — nothing to do.")
    raise SystemExit(0)

# A System User with no Page assigned reports every granular scope with no targets.
targets = [g for g in data.get("granular_scopes", []) if g.get("target_ids")]
if not targets:
    print()
    print("This System User has no Page assigned to it, so there is no Page token to derive.")
    print("Fix it in Business Settings -> Users -> System Users -> (your user):")
    print("  1. Add Assets -> Pages -> select your Page -> enable full control")
    print("  2. Add Assets -> Instagram accounts -> select your account")
    print("  3. Generate New Token, keeping the same permissions")
    print("  4. Put the new token in .env and re-run this script")
    raise SystemExit(1)

result = graph(page, fields="name,access_token")
if "error" in result or "access_token" not in result:
    print("Could not derive a Page token:", result.get("error", {}).get("message", result))
    raise SystemExit(1)

page_token = result["access_token"]
check = graph("debug_token", input_token=page_token).get("data", {})
if check.get("type") != "PAGE":
    print(f"Derived a token but it is type {check.get('type')}, not PAGE. Aborting.")
    raise SystemExit(1)

env = pathlib.Path(".env")
shutil.copy(env, ".env.bak")
text = env.read_text()
text = re.sub(r'^META_ACCESS_TOKEN=.*$', f'META_ACCESS_TOKEN={page_token}',
              text, count=1, flags=re.M)
env.write_text(text)

print()
print(f"Page token for '{result.get('name')}' written to .env ({len(page_token)} chars, value not printed).")
print(f"  type      : {check.get('type')}")
print(f"  expires_at: {check.get('expires_at')} (0 = never)")
print("  previous .env saved as .env.bak")
print()
print("Restart the app to pick it up.")
PY
