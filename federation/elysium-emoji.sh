#!/bin/sh
# Adds a custom emoji to Elysium through its admin page, logging in like a browser, since there's no other way to add one.
# Runs inside the driver service, with an admin's username and password, the shortcode, and the image as arguments.
set -eu

username=$1
password=$2
shortcode=$3
image=$4
base=https://elysium.test
jar=$(mktemp)

# The value of a hidden field on a page.
field() {
    printf '%s' "$1" | tr -d '\n' | sed -n "s/.*name=\"$2\" value=\"\([^\"]*\)\".*/\1/p"
}

page=$(curl -fsS -b "$jar" -c "$jar" "$base/login")
curl -fsS -o /dev/null -b "$jar" -c "$jar" "$base/login" \
    --data-urlencode "_csrf=$(field "$page" _csrf)" \
    --data-urlencode "username=$username" \
    --data-urlencode "password=$password"

page=$(curl -fsS -b "$jar" -c "$jar" "$base/admin/custom-emojis")
status=$(curl -sS -o /dev/null -w '%{http_code}' -b "$jar" -c "$jar" "$base/admin/custom-emojis" \
    -F "_csrf=$(field "$page" _csrf)" \
    -F "shortcode=$shortcode" \
    -F "image=@$image;type=image/png")
if [ "$status" != 302 ]; then
    echo "Adding :$shortcode: got a $status" >&2
    exit 1
fi
