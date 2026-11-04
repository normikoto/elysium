#!/usr/bin/env bash
# Starts the federation lab from nothing and checks that a real server can discover a user on Elysium.
# Usage: federation/smoke.sh gotosocial|mastodon
#
# The lab keeps running afterward, so that it can be looked into. See federation/README.md.
set -euo pipefail

target=${1:-}
case "$target" in
    gotosocial | mastodon) ;;
    *)
        echo "Usage: $0 gotosocial|mastodon" >&2
        exit 2
        ;;
esac

cd "$(dirname "$0")/.."
export COMPOSE_FILE=compose.federation.yaml
export COMPOSE_PROFILES=$target

# Not a secret: nothing can reach the lab from outside it. GoToSocial refuses weak passwords.
password='federation-lab-Password-7Q!x'
expected_actor=https://elysium.test/users/alice

driver() {
    docker compose run --rm -T driver "$@"
}

gts_admin() {
    docker compose exec -T gotosocial /gotosocial/gotosocial admin "$@"
}

fail() {
    echo "FAILED: $*" >&2
    exit 1
}

wait_for() {
    local url=$1
    echo "Waiting for $url"
    for _ in $(seq 1 90); do
        if driver curl -fsS -o /dev/null -m 5 "$url" 2>/dev/null; then
            return
        fi
        sleep 2
    done
    fail "$url never became ready"
}

check_elysium() {
    local webfinger actor instance
    webfinger=$(driver curl -fsS "https://elysium.test/.well-known/webfinger?resource=acct:alice@elysium.test")
    [[ $(jq -r '.links[] | select(.rel == "self") | .href' <<<"$webfinger") == "$expected_actor" ]] \
        || fail "WebFinger doesn't link to $expected_actor: $webfinger"

    actor=$(driver curl -fsS -H 'Accept: application/activity+json' "$expected_actor")
    [[ $(jq -r .id <<<"$actor") == "$expected_actor" ]] || fail "Unexpected actor document: $actor"

    instance=$(driver curl -fsS -H 'Accept: application/activity+json' https://elysium.test/actor)
    [[ $(jq -r .id <<<"$instance") == https://elysium.test/actor ]] || fail "Unexpected instance actor: $instance"
    echo 'Elysium serves its documents over HTTPS, through the proxy'
}

resolve_from_mastodon() {
    wait_for https://mastodon.test/health
    local output
    output=$(docker compose exec -T mastodon-web bin/rails runner '
        account = ResolveAccountService.new.call("alice@elysium.test")
        key = account&.keypairs&.find_by(uri: "#{account.uri}#main-key")
        puts "RESOLVED #{account&.uri} key=#{key&.public_key.present?}"')
    echo "$output"
    grep -qxF "RESOLVED $expected_actor key=true" <<<"$output" || fail "Mastodon didn't resolve alice@elysium.test"
}

resolve_from_gotosocial() {
    wait_for https://gts.test/api/v1/instance
    # After the server starts, because GoToSocial refuses to create users before its first run.
    gts_admin account create --username lab --email lab@gts.test --password "$password"
    gts_admin account confirm --username lab

    local token result
    token=$(driver sh /federation/gotosocial-token.sh lab@gts.test "$password")
    [[ -n $token ]] || fail "Couldn't get a GoToSocial access token"

    result=$(driver curl -fsS -G https://gts.test/api/v2/search \
        -H "Authorization: Bearer $token" \
        --data-urlencode 'q=@alice@elysium.test' \
        --data-urlencode 'resolve=true' \
        --data-urlencode 'type=accounts')
    echo "$result"
    [[ $(jq -r '.accounts[0].acct' <<<"$result") == alice@elysium.test ]] \
        || fail "GoToSocial didn't resolve alice@elysium.test"
}

# The '*' includes every profile, so that this also clears a lab left over from the other target.
docker compose --profile '*' down -v --remove-orphans

docker compose up -d

wait_for https://elysium.test/actuator/health
printf '%s\n' "$password" | docker compose run --rm -T elysium create-user alice
check_elysium

case "$target" in
    gotosocial) resolve_from_gotosocial ;;
    mastodon) resolve_from_mastodon ;;
esac

echo "PASSED: $target can discover alice@elysium.test"
