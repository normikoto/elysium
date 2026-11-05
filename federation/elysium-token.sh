#!/bin/sh
# Prints an access token for an Elysium user, by going through the login pages like a browser, since there's no other way to
# get one. Runs inside the driver service, with the username, the password, and optionally one scope as arguments.
set -eu

username=$1
password=$2
scope=${3:-write}
base=https://elysium.test
oob=urn:ietf:wg:oauth:2.0:oob
jar=$(mktemp)

# The value of a hidden field on a page.
field() {
    printf '%s' "$1" | tr -d '\n' | sed -n "s/.*name=\"$2\" value=\"\([^\"]*\)\".*/\1/p"
}

app=$(curl -fsS "$base/api/v1/apps" \
    --data-urlencode 'client_name=federation-lab' \
    --data-urlencode "redirect_uris=$oob" \
    --data-urlencode "scopes=$scope")
client_id=$(printf '%s' "$app" | sed -n 's/.*"client_id":"\([^"]*\)".*/\1/p')
client_secret=$(printf '%s' "$app" | sed -n 's/.*"client_secret":"\([^"]*\)".*/\1/p')

# Starts the session, which remembers this request while logging in, and leads to the login page.
page=$(curl -fsS -L -b "$jar" -c "$jar" -G "$base/oauth/authorize" \
    --data-urlencode "client_id=$client_id" \
    --data-urlencode "redirect_uri=$oob" \
    --data-urlencode 'response_type=code' \
    --data-urlencode "scope=$scope")
csrf=$(field "$page" _csrf)

# Logging in leads back to the request, which asks for consent.
page=$(curl -fsS -L -b "$jar" -c "$jar" "$base/login" \
    --data-urlencode "_csrf=$csrf" \
    --data-urlencode "username=$username" \
    --data-urlencode "password=$password")
# Both of the page's forms have the same value, and this finds the last one.
state=$(field "$page" state)

# The Authorize button. The result is a page showing the code.
page=$(curl -fsS -L -b "$jar" -c "$jar" "$base/oauth/authorize" \
    --data-urlencode "client_id=$client_id" \
    --data-urlencode "state=$state" \
    --data-urlencode "scope=$scope")
code=$(printf '%s' "$page" | tr -d '\n' | sed -n 's/.*<code>\([^<]*\)<\/code>.*/\1/p')
if [ -z "$code" ]; then
    echo 'No authorization code on the page after authorizing' >&2
    exit 1
fi

curl -fsS "$base/oauth/token" \
    --data-urlencode "redirect_uri=$oob" \
    --data-urlencode "client_id=$client_id" \
    --data-urlencode "client_secret=$client_secret" \
    --data-urlencode 'grant_type=authorization_code' \
    --data-urlencode "code=$code" \
    | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p'
