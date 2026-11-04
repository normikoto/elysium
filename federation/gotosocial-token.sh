#!/bin/sh
# Prints an access token for a GoToSocial user, by going through the login pages like a browser, since there's no other way
# to get one. Runs inside the driver service, with the email and password as arguments.
set -eu

email=$1
password=$2
base=https://gts.test
oob=urn:ietf:wg:oauth:2.0:oob
jar=$(mktemp)

app=$(curl -fsS "$base/api/v1/apps" \
    --data-urlencode 'client_name=federation-lab' \
    --data-urlencode "redirect_uris=$oob" \
    --data-urlencode 'scopes=read write')
client_id=$(printf '%s' "$app" | sed -n 's/.*"client_id":"\([^"]*\)".*/\1/p')
client_secret=$(printf '%s' "$app" | sed -n 's/.*"client_secret":"\([^"]*\)".*/\1/p')

# Starts the session, which remembers this request while signing in.
curl -fsS -o /dev/null -b "$jar" -c "$jar" -G "$base/oauth/authorize" \
    --data-urlencode "client_id=$client_id" \
    --data-urlencode "redirect_uri=$oob" \
    --data-urlencode 'response_type=code' \
    --data-urlencode 'scope=read write'

curl -fsS -o /dev/null -b "$jar" -c "$jar" "$base/auth/sign_in" \
    --data-urlencode "username=$email" \
    --data-urlencode "password=$password"

# The Allow button on the page that signing in leads to. The result is a page showing the code.
page=$(curl -fsS -L -b "$jar" -c "$jar" -d '' "$base/oauth/authorize")
code=$(printf '%s' "$page" | tr -d '\n' | sed -n 's/.*<code>\([^<]*\)<\/code>.*/\1/p')
if [ -z "$code" ]; then
    echo 'No authorization code on the page after allowing access' >&2
    exit 1
fi

curl -fsS "$base/oauth/token" \
    --data-urlencode "redirect_uri=$oob" \
    --data-urlencode "client_id=$client_id" \
    --data-urlencode "client_secret=$client_secret" \
    --data-urlencode 'grant_type=authorization_code' \
    --data-urlencode "code=$code" \
    | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p'
