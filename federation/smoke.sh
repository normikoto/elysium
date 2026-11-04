#!/usr/bin/env bash
# Starts the federation lab from nothing, and checks that a real server can discover a user on Elysium, deliver to it, follow
# and unfollow it, and receive its statuses and their deletion.
# Usage: federation/smoke.sh gotosocial|mastodon|misskey
#
# The lab keeps running afterward, so that it can be looked into. See federation/README.md.
set -euo pipefail

target=${1:-}
case "$target" in
    gotosocial | mastodon | misskey) ;;
    *)
        echo "Usage: $0 gotosocial|mastodon|misskey" >&2
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

# Leaves the URIs of a public and a followers-only status in public_uri and private_uri, for the other server to look for.
post_from_elysium() {
    local actor followers status
    actor=$(driver curl -fsS -H 'Accept: application/activity+json' "$expected_actor")
    [[ $(jq -r .followers <<<"$actor") == "$expected_actor/followers" ]] || fail "The actor has no followers collection: $actor"
    followers=$(driver curl -fsS -H 'Accept: application/activity+json' "$expected_actor/followers")
    [[ $(jq -r .totalItems <<<"$followers") == 1 ]] || fail "Unexpected followers collection: $followers"

    # Not local, because delete_from_elysium uses it too.
    elysium_token=$(driver sh /federation/elysium-token.sh alice "$password")
    [[ -n $elysium_token ]] || fail "Couldn't get an Elysium access token"
    public_uri=$(post_status "$elysium_token" public)
    private_uri=$(post_status "$elysium_token" private)
    expect_queued "$public_uri#create"
    expect_queued "$private_uri#create"

    status=$(driver curl -sS -o /dev/null -w '%{http_code}' -H 'Accept: application/activity+json' "$private_uri")
    [[ $status == 404 ]] || fail "Fetching a followers-only status got a $status, not a 404"
}

post_status() {
    driver curl -fsS https://elysium.test/api/v1/statuses \
        -H "Authorization: Bearer $1" \
        --data-urlencode "status=A $2 status from the lab" \
        --data-urlencode "visibility=$2" | jq -r .uri
}

# Passing means that the listener ran, so a failure after this is about the delivery or the other server.
expect_queued() {
    local found
    echo "Waiting for Elysium to queue $1"
    for _ in $(seq 1 30); do
        found=$(docker compose exec -T postgres psql -U elysium -d elysium -tAc \
            "SELECT count(*) FROM outbound_activity WHERE activity_id = '$1'")
        if [[ $found -eq 1 ]]; then
            return
        fi
        sleep 2
    done
    fail "Elysium never queued $1. Check its logs"
}

delete_status() {
    driver curl -fsS -o /dev/null -X DELETE "https://elysium.test/api/v1/statuses/${1##*/}" \
        -H "Authorization: Bearer $elysium_token"
}

# Deletes the statuses from post_from_elysium, for the other server to remove.
delete_from_elysium() {
    delete_status "$public_uri"
    delete_status "$private_uri"
    expect_queued "$public_uri#delete"
    expect_queued "$private_uri#delete"

    local status tombstone
    status=$(driver curl -sS -o /dev/null -w '%{http_code}' -H 'Accept: application/activity+json' "$public_uri")
    [[ $status == 410 ]] || fail "Fetching a deleted public status got a $status, not a 410"
    tombstone=$(driver curl -sS -H 'Accept: application/activity+json' "$public_uri")
    [[ $(jq -r .type <<<"$tombstone") == Tombstone ]] || fail "A deleted public status isn't a Tombstone: $tombstone"

    status=$(driver curl -sS -o /dev/null -w '%{http_code}' -H 'Accept: application/activity+json' "$private_uri")
    [[ $status == 404 ]] || fail "Fetching a deleted followers-only status got a $status, not a 404"
}

mastodon_expect_statuses() {
    local output
    # Not the home feed, because Mastodon doesn't fill those for users who haven't signed in lately.
    output=$(docker compose exec -T -e PUBLIC_URI="$public_uri" -e PRIVATE_URI="$private_uri" mastodon-web bin/rails runner '
        find = lambda do |uri|
            status = nil
            60.times do
                status = Status.uncached { Status.find_by(uri: uri) }
                break if status
                sleep 2
            end
            status
        end
        [ENV["PUBLIC_URI"], ENV["PRIVATE_URI"]].each do |uri|
            status = find.call(uri)
            puts "STATUS #{status&.visibility} #{status&.account&.uri}"
        end')
    echo "$output"
    grep -qxF "STATUS public $expected_actor" <<<"$output" || fail "Mastodon never stored the public status"
    grep -qxF "STATUS private $expected_actor" <<<"$output" || fail "Mastodon never stored the followers-only status"
    echo 'Mastodon stored both statuses, with the right visibility'
}

mastodon_expect_deleted() {
    local output
    output=$(docker compose exec -T -e PUBLIC_URI="$public_uri" -e PRIVATE_URI="$private_uri" mastodon-web bin/rails runner '
        [ENV["PUBLIC_URI"], ENV["PRIVATE_URI"]].each do |uri|
            gone = false
            60.times do
                gone = Status.uncached { Status.find_by(uri: uri) }.nil?
                break if gone
                sleep 2
            end
            puts "GONE #{gone}"
        end')
    echo "$output"
    [[ $(grep -cxF 'GONE true' <<<"$output") == 2 ]] || fail "Mastodon still has a deleted status"
    echo 'Mastodon removed both statuses'
}

gotosocial_visibility_of() {
    driver curl -fsS -G "https://gts.test/api/v1/accounts/$alice_id/statuses" -H "Authorization: Bearer $token" \
        | jq -r --arg uri "$1" '.[] | select(.uri == $uri) | .visibility'
}

gotosocial_expect_status() {
    local uri=$1 expected=$2 found
    echo "Waiting for GoToSocial to receive $uri"
    # What it has stored, and not a search, which could fetch a public status itself, and pass with no delivery.
    for _ in $(seq 1 60); do
        found=$(gotosocial_visibility_of "$uri")
        [[ -n $found ]] && break
        sleep 2
    done
    [[ $found == "$expected" ]] || fail "GoToSocial shows $uri as '$found', not '$expected'"
}

gotosocial_expect_statuses() {
    gotosocial_expect_status "$public_uri" public
    gotosocial_expect_status "$private_uri" private
    echo 'GoToSocial received both statuses, with the right visibility'

    # Only after they're stored. In GoToSocial 0.22.3, a timeline that first loads with exactly one status stops updating
    # until a restart, even for its own user's posts, and the lab user's mention of alice would be that one status.
    local home
    home=$(driver curl -fsS https://gts.test/api/v1/timelines/home -H "Authorization: Bearer $token")
    for uri in "$public_uri" "$private_uri"; do
        jq -e --arg uri "$uri" 'any(.[]; .uri == $uri)' <<<"$home" >/dev/null \
            || fail "$uri isn't in the home timeline of GoToSocial's lab user: $home"
    done
    echo "Both statuses are in the home timeline of GoToSocial's lab user"
}

gotosocial_expect_deleted() {
    local uri found
    for uri in "$public_uri" "$private_uri"; do
        echo "Waiting for GoToSocial to remove $uri"
        for _ in $(seq 1 60); do
            found=$(gotosocial_visibility_of "$uri")
            [[ -z $found ]] && break
            sleep 2
        done
        [[ -z $found ]] || fail "GoToSocial still has $uri"
    done
    echo 'GoToSocial removed both statuses'
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

# Misskey's API only takes POSTs of JSON, with the token in the body.
misskey_api() {
    local endpoint=$1 body=${2:-'{}'}
    driver curl -fsS "https://misskey.test/api/$endpoint" \
        -H 'Content-Type: application/json' \
        -d "$(jq -c --arg token "$token" '. + {i: $token}' <<<"$body")"
}

resolve_from_misskey() {
    wait_for https://misskey.test/healthz

    # The first account needs no token, and becomes an admin.
    local account
    account=$(driver curl -fsS https://misskey.test/api/admin/accounts/create \
        -H 'Content-Type: application/json' \
        -d "$(jq -cn --arg password "$password" '{username: "lab", password: $password}')")
    # Not local, because the other Misskey steps use it too.
    token=$(jq -r .token <<<"$account")
    [[ -n $token && $token != null ]] || fail "Couldn't get a Misskey access token: $account"

    # A new server refuses every other server until an admin changes this, and the config file has no setting for it.
    misskey_api admin/update-meta '{"federation": "all"}'

    local result
    # By username and host, which goes through WebFinger.
    result=$(misskey_api users/show '{"username": "alice", "host": "elysium.test"}')
    [[ $(jq -r .uri <<<"$result") == "$expected_actor" ]] || fail "Misskey didn't resolve alice@elysium.test: $result"
    echo 'Misskey resolved alice@elysium.test'
    # Not local, because the other Misskey steps use it too.
    alice_id=$(jq -r .id <<<"$result")
}

deliver_from_misskey() {
    misskey_api notes/create '{"text": "@alice@elysium.test Hello from the lab", "visibility": "public"}' >/dev/null
    expect_delivery misskey.test
}

misskey_user_body() {
    jq -cn --arg id "$alice_id" '{userId: $id}'
}

misskey_follow() {
    misskey_api following/create "$(misskey_user_body)" >/dev/null
    echo 'Waiting for Misskey to receive an Accept'
    local following=false
    for _ in $(seq 1 60); do
        following=$(misskey_api users/relation "$(misskey_user_body)" | jq -r '.[0].isFollowing')
        [[ $following == true ]] && break
        sleep 2
    done
    [[ $following == true ]] || fail "Misskey never received an Accept from Elysium"
    echo 'Misskey accepted the signed Accept'
    expect_followers misskey.test 1
}

misskey_unfollow() {
    misskey_api following/delete "$(misskey_user_body)" >/dev/null
    expect_followers misskey.test 0
}

# Twice, because each Undo between two actors has the same ID, with a new ID for each Follow. The Follow it embeds has an ID
# that's never sent, so each one only works by matching the two actors.
follow_from_misskey() {
    misskey_follow
    misskey_unfollow
    echo 'Following again'
    misskey_follow
    echo 'Unfollowing again, with the same Undo ID'
    misskey_unfollow
    echo 'Following a third time'
    misskey_follow

    # Without this, the checks above would still pass if Misskey stopped reusing IDs, and test nothing new.
    local counts expected='Follow 3 0
Undo 1 1'
    counts=$(docker compose exec -T postgres psql -U elysium -d elysium -tA -F ' ' -c \
        "SELECT type, count(*), count(*) FILTER (WHERE activity_id LIKE '%/undo') FROM inbound_activity
         WHERE actor_uri LIKE 'https://misskey.test/%' AND type IN ('Follow', 'Undo') GROUP BY type ORDER BY type")
    [[ $counts == "$expected" ]] || fail "Expected 3 Follow IDs and 1 reused Undo ID from Misskey, but found: $counts"
    echo 'Misskey used a new ID for each Follow, and the same ID for each Undo'
}

misskey_expect_status() {
    local uri=$1 expected_visibility=$2 expected_text=$3 note=''
    echo "Waiting for Misskey to receive $uri"
    # What it has stored, and not ap/show, which could fetch a public status itself, and pass with no delivery.
    for _ in $(seq 1 60); do
        note=$(misskey_api users/notes "$(jq -cn --arg id "$alice_id" '{userId: $id, limit: 100}')" \
            | jq -c --arg uri "$uri" '.[] | select(.uri == $uri)')
        [[ -n $note ]] && break
        sleep 2
    done
    [[ -n $note ]] || fail "Misskey never stored $uri"
    [[ $(jq -r .visibility <<<"$note") == "$expected_visibility" ]] \
        || fail "Misskey doesn't show $uri as $expected_visibility: $note"
    [[ $(jq -r .text <<<"$note") == "$expected_text" ]] || fail "Misskey doesn't show $uri as '$expected_text': $note"
}

misskey_expect_statuses() {
    misskey_expect_status "$public_uri" public 'A public status from the lab'
    misskey_expect_status "$private_uri" followers 'A private status from the lab'
    echo 'Misskey stored both statuses, with the right visibility and text'

    local timeline
    timeline=$(misskey_api notes/timeline '{"limit": 100}')
    for uri in "$public_uri" "$private_uri"; do
        jq -e --arg uri "$uri" 'any(.[]; .uri == $uri)' <<<"$timeline" >/dev/null \
            || fail "$uri isn't in the home timeline of Misskey's lab user: $timeline"
    done
    echo "Both statuses are in the home timeline of Misskey's lab user"
}

misskey_expect_deleted() {
    local uri note
    for uri in "$public_uri" "$private_uri"; do
        echo "Waiting for Misskey to remove $uri"
        for _ in $(seq 1 60); do
            note=$(misskey_api users/notes "$(jq -cn --arg id "$alice_id" '{userId: $id, limit: 100}')" \
                | jq -c --arg uri "$uri" '.[] | select(.uri == $uri)')
            [[ -z $note ]] && break
            sleep 2
        done
        [[ -z $note ]] || fail "Misskey still has $uri: $note"
    done
    echo 'Misskey removed both statuses'
}

# The '*' includes every profile, so that this also clears a lab left over from another target.
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
        post_from_elysium
        gotosocial_expect_statuses
        delete_from_elysium
        gotosocial_expect_deleted
        ;;
    mastodon)
        resolve_from_mastodon
        deliver_from_mastodon
        follow_from_mastodon
        post_from_elysium
        mastodon_expect_statuses
        delete_from_elysium
        mastodon_expect_deleted
        ;;
    misskey)
        resolve_from_misskey
        deliver_from_misskey
        follow_from_misskey
        post_from_elysium
        misskey_expect_statuses
        delete_from_elysium
        misskey_expect_deleted
        ;;
esac

echo "PASSED: $target can discover alice@elysium.test, deliver to it, follow and unfollow it, and receive its statuses"
