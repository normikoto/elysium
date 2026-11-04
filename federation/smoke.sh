#!/usr/bin/env bash
# Starts the federation lab from nothing, and checks that a real server can discover a user on Elysium, deliver to it, and
# follow and unfollow it.
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

    local status
    status=$(driver curl -sS -o /dev/null -w '%{http_code}' -H 'Content-Type: application/activity+json' -d '{}' \
        https://elysium.test/inbox)
    [[ $status == 401 ]] || fail "An unsigned delivery got a $status, not a 401"
    echo 'Elysium refuses an unsigned delivery'
}

# Passing means that Elysium verified the signature, which needs a fetch of the sender's key from its server.
expect_delivery() {
    local server=$1 found
    echo "Waiting for a delivery from $server"
    for _ in $(seq 1 60); do
        # By server, because Mastodon's actor URIs have an ID in them.
        found=$(docker compose exec -T postgres psql -U elysium -d elysium -tAc \
            "SELECT count(*) FROM inbound_activity WHERE type = 'Create' AND actor_uri LIKE 'https://$server/%'")
        if [[ $found -gt 0 ]]; then
            echo "Elysium accepted a Create from $server"
            return
        fi
        sleep 2
    done
    fail "Nothing was delivered from $server. Elysium only logs why it refused one at debug level"
}

# Passing the first time means that the other server's Follow was stored here. Elysium has to fetch the follower's document.
expect_followers() {
    local server=$1 expected=$2 found
    echo "Waiting for Elysium to have $expected followers from $server"
    for _ in $(seq 1 60); do
        found=$(docker compose exec -T postgres psql -U elysium -d elysium -tAc \
            "SELECT count(*) FROM follow f JOIN actor a ON a.id = f.follower_id
             WHERE f.state = 'accepted' AND a.domain = '$server'")
        if [[ $found -eq $expected ]]; then
            echo "Elysium has $expected followers from $server"
            return
        fi
        sleep 2
    done
    fail "Elysium has $found followers from $server, not $expected. Check its failed jobs and logs"
}

mastodon_follow() {
    local output
    # Inside one process, because starting Rails for each check would take most of the time this waits for.
    output=$(docker compose exec -T mastodon-web bin/rails runner '
        lab = Account.find_local("lab")
        alice = ResolveAccountService.new.call("alice@elysium.test")
        FollowService.new.call(lab, alice)
        # Uncached, because Rails would otherwise give the first answer every time.
        following = -> { Follow.uncached { Follow.exists?(account: lab, target_account: alice) } }
        60.times do
            break if following.call
            sleep 2
        end
        puts "FOLLOWING #{following.call}"')
    echo "$output"
    grep -qxF 'FOLLOWING true' <<<"$output" || fail "Mastodon never received an Accept from Elysium"
    echo 'Mastodon accepted the signed Accept, with AUTHORIZED_FETCH on'
    expect_followers mastodon.test 1
}

follow_from_mastodon() {
    mastodon_follow

    docker compose exec -T mastodon-web bin/rails runner '
        UnfollowService.new.call(Account.find_local("lab"), ResolveAccountService.new.call("alice@elysium.test"))'
    expect_followers mastodon.test 0

    echo 'Following again'
    mastodon_follow
}

gotosocial_is_following() {
    driver curl -fsS -G https://gts.test/api/v1/accounts/relationships \
        -H "Authorization: Bearer $token" \
        --data-urlencode "id[]=$alice_id" | jq -r '.[0].following'
}

gotosocial_follow() {
    driver curl -fsS -o /dev/null -X POST "https://gts.test/api/v1/accounts/$alice_id/follow" \
        -H "Authorization: Bearer $token"
    echo 'Waiting for GoToSocial to receive an Accept'
    local following=false
    for _ in $(seq 1 60); do
        following=$(gotosocial_is_following)
        [[ $following == true ]] && break
        sleep 2
    done
    [[ $following == true ]] || fail "GoToSocial never received an Accept from Elysium"
    echo 'GoToSocial accepted the signed Accept'
    expect_followers gts.test 1
}

follow_from_gotosocial() {
    gotosocial_follow

    driver curl -fsS -o /dev/null -X POST "https://gts.test/api/v1/accounts/$alice_id/unfollow" \
        -H "Authorization: Bearer $token"
    expect_followers gts.test 0

    echo 'Following again'
    gotosocial_follow
}

deliver_from_mastodon() {
    # Mastodon answers a 404 for the actor of an account that isn't approved, which Elysium would remember for a while.
    docker compose exec -T mastodon-web bin/tootctl accounts create lab --email lab@mastodon.test --confirmed --approve
    docker compose exec -T mastodon-web bin/rails runner '
        PostStatusService.new.call(Account.find_local("lab"), text: "@alice@elysium.test Hello from the lab")'
    expect_delivery mastodon.test
}

deliver_from_gotosocial() {
    driver curl -fsS -o /dev/null https://gts.test/api/v1/statuses \
        -H "Authorization: Bearer $token" \
        --data-urlencode 'status=@alice@elysium.test Hello from the lab' \
        --data-urlencode 'visibility=public'
    expect_delivery gts.test
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

    local result
    # Not local, because deliver_from_gotosocial uses it too.
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
    # Not local, because follow_from_gotosocial uses it too.
    alice_id=$(jq -r '.accounts[0].id' <<<"$result")
}

# The '*' includes every profile, so that this also clears a lab left over from the other target.
docker compose --profile '*' down -v --remove-orphans

docker compose up -d

wait_for https://elysium.test/actuator/health
printf '%s\n' "$password" | docker compose run --rm -T elysium create-user alice
check_elysium

case "$target" in
    gotosocial)
        resolve_from_gotosocial
        deliver_from_gotosocial
        follow_from_gotosocial
        ;;
    mastodon)
        resolve_from_mastodon
        deliver_from_mastodon
        follow_from_mastodon
        ;;
esac

echo "PASSED: $target can discover alice@elysium.test, deliver to it, and follow and unfollow it"
