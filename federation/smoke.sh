#!/usr/bin/env bash
# Starts the federation lab from nothing, and checks that a real server can discover a user on Elysium, deliver to it, follow
# and unfollow it, including while it approves followers by hand, take its blocks and their Undos, be followed and unfollowed by it, and receive its statuses, their attachments, edits and deletion, and profile changes,
# apart from edits on Misskey, which doesn't apply them, and that Elysium takes the other server's profile changes and status
# edits, apart from on Misskey, which can't edit, shows Mastodon's attachments, and removes a status and an account that the other server deletes. Both
# servers also like and unlike, and boost and unboost, each other's posts, Elysium fetches a post that a followed account
# boosts, and it backfills a followed account's older post from its outbox.
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
            expect_remote_status "$server"
            return
        fi
        sleep 2
    done
    fail "Nothing was delivered from $server. Elysium only logs why it refused one at debug level"
}

# The lab's post mentions alice, which is what makes it hers to store. Read through the API, so that this also checks that the
# sanitized HTML keeps the text and the mention's link.
expect_remote_status() {
    local server=$1 statuses status
    echo "Waiting for Elysium to show lab@$server's status"
    for _ in $(seq 1 60); do
        statuses=$(driver curl -fsS -G https://elysium.test/api/v1/timelines/public --data-urlencode remote=true)
        status=$(jq -c --arg acct "lab@$server" 'map(select(.account.acct == $acct)) | first // empty' <<<"$statuses")
        if [[ -n $status ]]; then
            jq -e '.content | contains("Hello from the lab") and contains("<a href=\"https://elysium.test/")' \
                <<<"$status" >/dev/null || fail "Elysium stored lab@$server's status with unexpected content: $status"
            jq -e '.uri | startswith("https://")' <<<"$status" >/dev/null \
                || fail "Elysium stored lab@$server's status without its URI: $status"
            echo "Elysium shows lab@$server's status"
            return
        fi
        sleep 2
    done
    fail "Elysium didn't store lab@$server's status. Check /admin/db-scheduler, and its logs at debug level"
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

# Passing means that Elysium found the other server's lab user through WebFinger, sent it a Follow that it accepted, and sent
# an Undo that it took. The second argument, if given, is a function that approves the request, for a server whose users
# approve followers by hand.
# post has the lab user post with no mention, so the post only reaches alice through her follow. It's also called before
# alice follows, with other text, for a post that Elysium can only get by backfilling from the lab user's outbox.
follow_from_elysium() {
    local domain=$1 post=$2 approve=${3:-} read_token write_token lab lab_id lab_uri following status
    read_token=$(driver sh /federation/elysium-token.sh alice "$password" read)
    write_token=$(driver sh /federation/elysium-token.sh alice "$password" write)
    [[ -n $read_token && -n $write_token ]] || fail "Couldn't get Elysium access tokens"

    lab=$(driver curl -fsS -G https://elysium.test/api/v1/accounts/lookup \
        -H "Authorization: Bearer $read_token" --data-urlencode "acct=lab@$domain") \
        || fail "Elysium couldn't find lab@$domain. Check its logs"
    lab_id=$(jq -r .id <<<"$lab")
    lab_uri=$(jq -r .uri <<<"$lab")
    echo "Elysium found lab@$domain as $lab_uri"

    "$post" 'Posted before alice followed the lab'
    driver curl -fsS -o /dev/null -X POST "https://elysium.test/api/v1/accounts/$lab_id/follow" \
        -H "Authorization: Bearer $write_token"
    [[ -z $approve ]] || "$approve"
    echo "Waiting for lab@$domain to accept alice's Follow"
    following=false
    for _ in $(seq 1 60); do
        following=$(driver curl -fsS -G https://elysium.test/api/v1/accounts/relationships \
            -H "Authorization: Bearer $read_token" --data-urlencode "id[]=$lab_id" | jq -r '.[0].following')
        [[ $following == true ]] && break
        sleep 2
    done
    [[ $following == true ]] || fail "lab@$domain never accepted alice's Follow. Check /admin/db-scheduler"

    local page
    page=$(driver curl -fsS -H 'Accept: application/activity+json' "$expected_actor/following?page=true")
    jq -e --arg uri "$lab_uri" '.orderedItems == [$uri]' <<<"$page" >/dev/null \
        || fail "alice's following collection doesn't list $lab_uri: $page"
    echo "lab@$domain accepted, and alice's following collection lists it"

    expect_backfilled "$domain" "$read_token" "$lab_id"
    "$post"
    expect_home_status "$domain" "$read_token"
    boost_other_from_peer "$domain" "$read_token"
    rename_lab "$domain"

    driver curl -fsS -o /dev/null -X POST "https://elysium.test/api/v1/accounts/$lab_id/unfollow" \
        -H "Authorization: Bearer $write_token"
    expect_delivered "$expected_actor#follows/%/undo"
    status=$(driver curl -fsS -H 'Accept: application/activity+json' "$expected_actor/following" | jq -r .totalItems)
    [[ $status == 0 ]] || fail "alice's following collection still has $status items after unfollowing"
    echo "lab@$domain took alice's Undo"
}

# The lab user boosts a post by another account on its server, which Elysium hasn't been sent, so Elysium has to fetch it
# before alice's home timeline can show the boost. Run while alice follows the lab user.
boost_other_from_peer() {
    local domain=$1 read_token=$2 other_token other_id status_id output account home
    case "$target" in
        mastodon)
            docker compose exec -T mastodon-web bin/tootctl accounts create other --email other@mastodon.test --confirmed --approve
            output=$(docker compose exec -T mastodon-web bin/rails runner '
                status = PostStatusService.new.call(Account.find_local("other"), text: "Hello from another lab account")
                ReblogService.new.call(Account.find_local("lab"), status)
                puts "DONE"')
            grep -qxF DONE <<<"$output" || fail "Mastodon couldn't boost other's post: $output"
            ;;
        gotosocial)
            gts_admin account create --username other --email other@gts.test --password "$password"
            gts_admin account confirm --username other
            other_token=$(driver sh /federation/gotosocial-token.sh other@gts.test "$password")
            [[ -n $other_token ]] || fail "Couldn't get a GoToSocial access token for other"
            status_id=$(driver curl -fsS https://gts.test/api/v1/statuses -H "Authorization: Bearer $other_token" \
                --data-urlencode 'status=Hello from another lab account' --data-urlencode 'visibility=public' | jq -r .id)
            driver curl -fsS -o /dev/null -X POST "https://gts.test/api/v1/statuses/$status_id/reblog" \
                -H "Authorization: Bearer $token"
            ;;
        misskey)
            account=$(misskey_api admin/accounts/create \
                "$(jq -cn --arg password "$password" '{username: "other", password: $password}')")
            other_token=$(jq -r .token <<<"$account")
            [[ -n $other_token && $other_token != null ]] || fail "Couldn't create Misskey's other account: $account"
            status_id=$(driver curl -fsS https://misskey.test/api/notes/create -H 'Content-Type: application/json' \
                -d "$(jq -cn --arg token "$other_token" '{i: $token, text: "Hello from another lab account", visibility: "public"}')" \
                | jq -r .createdNote.id)
            misskey_api notes/create "$(jq -cn --arg id "$status_id" '{renoteId: $id}')" >/dev/null
            ;;
    esac
    echo "Waiting for lab@$domain's boost of other@$domain's post to reach alice's home timeline"
    local found=false
    for _ in $(seq 1 60); do
        home=$(driver curl -fsS https://elysium.test/api/v1/timelines/home -H "Authorization: Bearer $read_token")
        if jq -e --arg lab "lab@$domain" --arg other "other@$domain" \
            'any(.[]; .account.acct == $lab and .reblog.account.acct == $other
                and (.reblog.content | contains("Hello from another lab account")))' <<<"$home" >/dev/null; then
            found=true
            break
        fi
        sleep 2
    done
    [[ $found == true ]] || fail "lab@$domain's boost never reached alice's home timeline: $home"
    echo "Elysium fetched other@$domain's post, and alice's home timeline shows lab@$domain's boost of it"

    case "$target" in
        mastodon)
            output=$(docker compose exec -T mastodon-web bin/rails runner '
                original = Account.find_local("other").statuses.order(:id).last
                RemoveStatusService.new.call(Account.find_local("lab").statuses.find_by!(reblog_of_id: original.id))
                puts "DONE"')
            grep -qxF DONE <<<"$output" || fail "Mastodon couldn't unboost other's post: $output"
            ;;
        gotosocial)
            driver curl -fsS -o /dev/null -X POST "https://gts.test/api/v1/statuses/$status_id/unreblog" \
                -H "Authorization: Bearer $token"
            ;;
        misskey)
            misskey_api notes/unrenote "$(jq -cn --arg id "$status_id" '{noteId: $id}')" >/dev/null
            ;;
    esac
    for _ in $(seq 1 60); do
        home=$(driver curl -fsS https://elysium.test/api/v1/timelines/home -H "Authorization: Bearer $read_token")
        if ! jq -e --arg lab "lab@$domain" 'any(.[]; .account.acct == $lab and .reblog != null)' <<<"$home" >/dev/null; then
            echo "Elysium took lab@$domain's Undo of the boost"
            return
        fi
        sleep 2
    done
    fail "lab@$domain's boost is still in alice's home timeline after it was undone"
}

# Passing means that Elysium fetched the lab user's outbox once alice's follow was accepted, read its first page, and
# stored the post from before she followed, which was never sent to Elysium.
expect_backfilled() {
    local domain=$1 read_token=$2 lab_id=$3 statuses
    echo "Waiting for Elysium to backfill lab@$domain's post from before alice followed"
    for _ in $(seq 1 60); do
        statuses=$(driver curl -fsS "https://elysium.test/api/v1/accounts/$lab_id/statuses" \
            -H "Authorization: Bearer $read_token")
        if jq -e 'any(.[]; .content | contains("Posted before alice followed the lab"))' <<<"$statuses" >/dev/null; then
            echo "Elysium backfilled lab@$domain's older post from its outbox"
            return
        fi
        sleep 2
    done
    fail "lab@$domain's older post was never backfilled: $statuses. Check /admin/db-scheduler, and Elysium's logs at debug level"
}

expect_home_status() {
    local domain=$1 read_token=$2 home
    echo "Waiting for lab@$domain's post to reach alice's home timeline"
    for _ in $(seq 1 60); do
        home=$(driver curl -fsS https://elysium.test/api/v1/timelines/home -H "Authorization: Bearer $read_token")
        if jq -e --arg acct "lab@$domain" \
            'any(.[]; .account.acct == $acct and (.content | contains("Hello to followers from the lab")))' \
            <<<"$home" >/dev/null; then
            echo "lab@$domain's post is in alice's home timeline"
            return
        fi
        sleep 2
    done
    fail "lab@$domain's post never reached alice's home timeline. Check /admin/db-scheduler, and Elysium's logs at debug level"
}

post_from_mastodon() {
    docker compose exec -T -e TEXT="${1:-Hello to followers from the lab}" mastodon-web bin/rails runner '
        PostStatusService.new.call(Account.find_local("lab"), text: ENV.fetch("TEXT"))'
}

post_from_gotosocial() {
    driver curl -fsS -o /dev/null https://gts.test/api/v1/statuses \
        -H "Authorization: Bearer $token" \
        --data-urlencode "status=${1:-Hello to followers from the lab}" \
        --data-urlencode 'visibility=public'
}

post_from_misskey() {
    misskey_api notes/create "$(jq -cn --arg text "${1:-Hello to followers from the lab}" \
        '{text: $text, visibility: "public"}')" >/dev/null
}

# GoToSocial's users approve followers by hand unless they change a setting, so alice's request waits for this.
gotosocial_approve() {
    local requests
    echo "Waiting for GoToSocial to receive alice's Follow"
    for _ in $(seq 1 60); do
        requests=$(driver curl -fsS https://gts.test/api/v1/follow_requests -H "Authorization: Bearer $token")
        jq -e --arg id "$alice_id" 'any(.[]; .id == $id)' <<<"$requests" >/dev/null && break
        sleep 2
    done
    driver curl -fsS -o /dev/null -X POST "https://gts.test/api/v1/follow_requests/$alice_id/authorize" \
        -H "Authorization: Bearer $token" || fail "GoToSocial never received alice's Follow"
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

edited_text='An edited public status from the lab'

# Posts and edits a status of its own, which isn't deleted later: GoToSocial 0.22.3 fails to delete a remote status once it's
# applied an edit to it. Misskey doesn't apply edits to notes, so for Misskey, passing only means that it accepted the Update.
edit_from_elysium() {
    local note
    edited_uri=$(post_status "$elysium_token" public)
    expect_delivered "$edited_uri#create"
    driver curl -fsS -o /dev/null -X PUT "https://elysium.test/api/v1/statuses/${edited_uri##*/}" \
        -H "Authorization: Bearer $elysium_token" --data-urlencode "status=$edited_text"
    note=$(driver curl -fsS -H 'Accept: application/activity+json' "$edited_uri")
    [[ $(jq -r .updated <<<"$note") != null ]] || fail "The edited status has no updated: $note"
    expect_delivered "$edited_uri#updates/%"
    if [[ $target != misskey ]]; then
        "${target}_expect_edited"
        echo "$target shows the edited status"
    fi
}

media_text='A picture from the lab'

# Posts a sensitive status with an image, which the other server has to store with the attachment, linked from here.
media_from_elysium() {
    local media_id note
    media_id=$(driver curl -fsS https://elysium.test/api/v2/media -H "Authorization: Bearer $elysium_token" \
        -F file=@/federation/fixtures/image.png -F 'description=A gradient' | jq -r .id)
    [[ -n $media_id && $media_id != null ]] || fail "Elysium refused the upload"
    media_uri=$(driver curl -fsS https://elysium.test/api/v1/statuses -H "Authorization: Bearer $elysium_token" \
        --data-urlencode "status=$media_text" --data-urlencode 'sensitive=true' --data-urlencode "media_ids[]=$media_id" \
        | jq -r .uri)
    note=$(driver curl -fsS -H 'Accept: application/activity+json' "$media_uri")
    jq -e '.attachment[0].type == "Document" and .attachment[0].mediaType == "image/webp"' <<<"$note" >/dev/null \
        || fail "The status's Note has no attachment: $note"
    expect_delivered "$media_uri#create"
    "${target}_expect_media"
    echo "$target shows the status's attachment"
}

mastodon_expect_media() {
    local output
    output=$(docker compose exec -T -e MEDIA_URI="$media_uri" mastodon-web bin/rails runner '
        status = nil
        60.times do
            status = Status.uncached { Status.find_by(uri: ENV["MEDIA_URI"]) }
            break if status
            sleep 2
        end
        media = status&.media_attachments&.to_a || []
        puts "MEDIA #{media.size} #{status&.sensitive} #{media.first&.remote_url}"')
    echo "$output"
    grep -qE '^MEDIA 1 true https://elysium\.test/media/' <<<"$output" \
        || fail "Mastodon didn't store $media_uri with its attachment"
}

gotosocial_expect_media() {
    local found=''
    for _ in $(seq 1 60); do
        found=$(driver curl -fsS -G "https://gts.test/api/v1/accounts/$alice_id/statuses" -H "Authorization: Bearer $token" \
            | jq -c --arg uri "$media_uri" '.[] | select(.uri == $uri)')
        [[ -n $found ]] && break
        sleep 2
    done
    jq -e '.sensitive and (.media_attachments | length == 1)
            and (.media_attachments[0].remote_url | startswith("https://elysium.test/media/"))' <<<"$found" >/dev/null \
        || fail "GoToSocial doesn't show $media_uri with its attachment: $found"
}

misskey_expect_media() {
    local note=''
    for _ in $(seq 1 60); do
        note=$(misskey_api users/notes "$(jq -cn --arg id "$alice_id" '{userId: $id, limit: 100}')" \
            | jq -c --arg uri "$media_uri" '.[] | select(.uri == $uri)')
        [[ -n $note ]] && break
        sleep 2
    done
    jq -e '.files | length == 1' <<<"$note" >/dev/null || fail "Misskey doesn't show $media_uri with its attachment: $note"
}

# Mastodon's lab user posts an image to alice, and Elysium has to show it, linked from Mastodon. It mentions her, since she's
# unfollowed lab by now.
media_from_mastodon() {
    local statuses status
    docker compose exec -T mastodon-web bin/rails runner '
        STDIN.binmode
        file = Tempfile.new(["lab", ".png"], binmode: true)
        file.write(STDIN.read)
        file.rewind
        account = Account.find_local("lab")
        media = account.media_attachments.create!(file: file, description: "A gradient from Mastodon")
        PostStatusService.new.call(account, text: "@alice@elysium.test Media from the lab", media_ids: [media.id])' \
        <federation/fixtures/image.png
    echo "Waiting for Elysium to show lab@mastodon.test's status with media"
    for _ in $(seq 1 60); do
        statuses=$(driver curl -fsS -G https://elysium.test/api/v1/timelines/public --data-urlencode remote=true)
        status=$(jq -c 'map(select(.content | contains("Media from the lab"))) | first // empty' <<<"$statuses")
        # Its attachment is downloaded after the status is stored, so this waits for that too.
        if [[ -n $status ]] && jq -e '.media_attachments[0].url | startswith("https://elysium.test/media/")' \
            <<<"$status" >/dev/null; then
            jq -e '(.media_attachments | length == 1) and .media_attachments[0].type == "image"
                    and (.media_attachments[0].remote_url | startswith("https://mastodon.test/"))
                    and .media_attachments[0].description == "A gradient from Mastodon"' <<<"$status" >/dev/null \
                || fail "Elysium shows lab@mastodon.test's status without its attachment: $status"
            echo "Elysium shows lab@mastodon.test's attachment, served from Elysium"
            return
        fi
        sleep 2
    done
    fail "Elysium didn't store and download lab@mastodon.test's status with media: $status. Check /admin/db-scheduler"
}

mastodon_expect_edited() {
    local output
    output=$(docker compose exec -T -e EDITED_URI="$edited_uri" -e EXPECTED="$edited_text" mastodon-web bin/rails runner '
        status = nil
        60.times do
            status = Status.uncached { Status.find_by(uri: ENV["EDITED_URI"]) }
            break if status&.edited_at && status.text.include?(ENV["EXPECTED"])
            sleep 2
        end
        puts "EDITED #{status&.edited_at.present? && status.text.include?(ENV["EXPECTED"])}"')
    echo "$output"
    grep -qxF 'EDITED true' <<<"$output" || fail "Mastodon didn't take the edit of $edited_uri"
}

gotosocial_expect_edited() {
    local found=''
    for _ in $(seq 1 60); do
        found=$(driver curl -fsS -G "https://gts.test/api/v1/accounts/$alice_id/statuses" -H "Authorization: Bearer $token" \
            | jq -r --arg uri "$edited_uri" '.[] | select(.uri == $uri) | .content')
        [[ $found == *"$edited_text"* ]] && return
        sleep 2
    done
    fail "GoToSocial shows $edited_uri as '$found', without the edit"
}

display_name='Alice in the lab'

# The other server's lab user still follows alice, so it gets the Update, and has to show the new name.
update_profile_from_elysium() {
    local actor
    driver curl -fsS -o /dev/null -X PATCH https://elysium.test/api/v1/accounts/update_credentials \
        -H "Authorization: Bearer $elysium_token" --data-urlencode "display_name=$display_name"
    actor=$(driver curl -fsS -H 'Accept: application/activity+json' "$expected_actor")
    [[ $(jq -r .name <<<"$actor") == "$display_name" ]] || fail "The actor document doesn't have the new name: $actor"
    expect_delivered "$expected_actor#profile/%"
    "${target}_expect_display_name"
    echo "$target shows alice's new display name"
}

gotosocial_expect_display_name() {
    local found=''
    for _ in $(seq 1 60); do
        found=$(driver curl -fsS "https://gts.test/api/v1/accounts/$alice_id" -H "Authorization: Bearer $token" \
            | jq -r .display_name)
        [[ $found == "$display_name" ]] && return
        sleep 2
    done
    fail "GoToSocial shows alice's display name as '$found', not '$display_name'"
}

mastodon_expect_display_name() {
    local output
    output=$(docker compose exec -T -e ACTOR_URI="$expected_actor" -e EXPECTED="$display_name" mastodon-web bin/rails runner '
        name = nil
        60.times do
            name = Account.uncached { Account.find_by(uri: ENV["ACTOR_URI"]) }&.display_name
            break if name == ENV["EXPECTED"]
            sleep 2
        end
        puts "NAME #{name}"')
    echo "$output"
    grep -qxF "NAME $display_name" <<<"$output" || fail "Mastodon didn't take alice's new display name"
}

misskey_expect_display_name() {
    local found=''
    for _ in $(seq 1 60); do
        found=$(misskey_api users/show "$(misskey_user_body)" | jq -r .name)
        [[ $found == "$display_name" ]] && return
        sleep 2
    done
    fail "Misskey shows alice's display name as '$found', not '$display_name'"
}

# Waits for the jobs that send an activity to finish. A refused delivery is kept as a failed job, so this also means that the
# other server accepted it.
expect_delivered() {
    local queued jobs
    echo "Waiting for $1 to be delivered"
    for _ in $(seq 1 60); do
        queued=$(docker compose exec -T postgres psql -U elysium -d elysium -tAc \
            "SELECT count(*) FROM outbound_activity WHERE activity_id LIKE '$1'")
        jobs=$(docker compose exec -T postgres psql -U elysium -d elysium -tAc \
            "SELECT count(*) FROM scheduled_tasks
             WHERE task_name = 'activity-delivery'
               AND convert_from(task_data, 'UTF8')::json ->> 'activityId' LIKE '$1'")
        if [[ $queued -ge 1 && $jobs -eq 0 ]]; then
            return
        fi
        sleep 2
    done
    fail "$1 wasn't delivered: $queued activities and $jobs unfinished jobs. Check /admin/db-scheduler"
}

# An admin can't suspend their own account, and alice is the only admin, so this makes another. Each step waits for the last one's delivery,
# since jobs have no order and each activity only says what's true now.
moderate_from_elysium() {
    local moderator_token alice_id status actor
    printf '%s\n' "$password" | docker compose run --rm -T elysium create-user moderator
    docker compose run --rm -T elysium set-role moderator admin
    moderator_token=$(driver sh /federation/elysium-token.sh moderator "$password" admin:write:accounts)
    [[ -n $moderator_token ]] || fail "Couldn't get a moderator's access token"
    alice_id=$(docker compose exec -T postgres psql -U elysium -d elysium -tAc \
        "SELECT id FROM actor WHERE username = 'alice' AND is_local")

    driver curl -fsS -o /dev/null -X POST "https://elysium.test/api/v1/admin/accounts/$alice_id/action" \
        -H "Authorization: Bearer $moderator_token" --data-urlencode 'type=suspend'
    actor=$(driver curl -fsS -H 'Accept: application/activity+json' "$expected_actor")
    [[ $(jq -r .suspended <<<"$actor") == true ]] || fail "A suspended actor doesn't say so: $actor"
    expect_delivered "$expected_actor#suspended/%"
    [[ $target != mastodon ]] || mastodon_expect_suspended true

    driver curl -fsS -o /dev/null -X POST "https://elysium.test/api/v1/admin/accounts/$alice_id/unsuspend" \
        -H "Authorization: Bearer $moderator_token"
    actor=$(driver curl -fsS -H 'Accept: application/activity+json' "$expected_actor")
    [[ $(jq -r .suspended <<<"$actor") == null ]] || fail "An unsuspended actor still says it's suspended: $actor"
    expect_delivered "$expected_actor#unsuspended/%"
    [[ $target != mastodon ]] || mastodon_expect_suspended false

    driver curl -fsS -o /dev/null -X DELETE "https://elysium.test/api/v1/admin/accounts/$alice_id" \
        -H "Authorization: Bearer $moderator_token"
    status=$(driver curl -sS -o /dev/null -w '%{http_code}' -H 'Accept: application/activity+json' "$expected_actor")
    [[ $status == 410 ]] || fail "Fetching a deleted actor got a $status, not a 410"
    expect_delivered "$expected_actor#delete"
    [[ $target != mastodon ]] || mastodon_expect_suspended removed
}

# true or false for whether it's suspended, or removed, which is what Mastodon does with an actor that's been deleted.
mastodon_expect_suspended() {
    local output
    output=$(docker compose exec -T -e ACTOR_URI="$expected_actor" -e EXPECTED="$1" mastodon-web bin/rails runner '
        matched = false
        60.times do
            account = Account.uncached { Account.find_by(uri: ENV["ACTOR_URI"]) }
            matched = (account.nil? ? "removed" : account.suspended?.to_s) == ENV["EXPECTED"]
            break if matched
            sleep 2
        end
        puts "MATCHED #{matched}"')
    echo "$output"
    grep -qxF 'MATCHED true' <<<"$output" || fail "Mastodon's copy of alice didn't become: $1"
}

mastodon_expect_statuses() {
    local output
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
        # By ID, because a feed only loads the IDs of its statuses.
        feed = HomeFeed.new(Account.find_local("lab"))
        in_feed = lambda do |status|
            found = false
            30.times do
                found = Status.uncached { feed.get(100).map(&:id).include?(status.id) }
                break if found
                sleep 2
            end
            found
        end
        [ENV["PUBLIC_URI"], ENV["PRIVATE_URI"]].each do |uri|
            status = find.call(uri)
            puts "STATUS #{status&.visibility} #{status&.account&.uri}"
            puts "IN FEED #{status.present? && in_feed.call(status)}"
        end')
    echo "$output"
    grep -qxF "STATUS public $expected_actor" <<<"$output" || fail "Mastodon never stored the public status"
    grep -qxF "STATUS private $expected_actor" <<<"$output" || fail "Mastodon never stored the followers-only status"
    echo 'Mastodon stored both statuses, with the right visibility'
    [[ $(grep -cxF 'IN FEED true' <<<"$output") == 2 ]] || fail "A status isn't in the home feed of Mastodon's lab user"
    echo "Both statuses are in the home feed of Mastodon's lab user"
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
    # Signed in, because Mastodon doesn't fill the home feeds of users who haven't signed in lately.
    docker compose exec -T mastodon-web bin/rails runner '
        lab = Account.find_local("lab")
        lab.user.update!(current_sign_in_at: Time.now.utc)
        PostStatusService.new.call(lab, text: "@alice@elysium.test Hello from the lab")'
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

# The lab user's status that mentions alice, as Elysium stored it. Its Delete goes to alice as someone it mentions, so this
# doesn't depend on any follow.
mentioned_status_uri() {
    local server=$1
    docker compose exec -T postgres psql -U elysium -d elysium -tAc \
        "SELECT s.uri FROM status s JOIN actor a ON a.id = s.actor_id
         WHERE a.domain = '$server' AND s.text LIKE '%Hello from the lab%'"
}

# Passing means that Elysium took the other server's Delete, verified as coming from the status's author.
expect_status_removed() {
    local server=$1 uri=$2 found
    echo "Waiting for Elysium to remove $uri"
    for _ in $(seq 1 60); do
        found=$(docker compose exec -T postgres psql -U elysium -d elysium -tAc \
            "SELECT count(*) FROM status WHERE uri = '$uri'")
        if [[ $found -eq 0 ]]; then
            echo "Elysium removed lab@$server's deleted status"
            return
        fi
        sleep 2
    done
    fail "Elysium still has $uri after $server deleted it. Check /admin/db-scheduler, and its logs at debug level"
}

# Passing means that Elysium took the other server's Delete of its actor, which leaves the row, marked deleted, with nothing
# else stored about it.
expect_actor_deleted() {
    local server=$1 username=$2 found
    echo "Waiting for Elysium to delete $username@$server"
    for _ in $(seq 1 60); do
        found=$(docker compose exec -T postgres psql -U elysium -d elysium -tAc \
            "SELECT count(*) FROM actor WHERE domain = '$server' AND username = '$username' AND deleted_at IS NOT NULL")
        [[ $found -eq 1 ]] && break
        sleep 2
    done
    [[ $found -eq 1 ]] || fail "Elysium didn't delete $username@$server. Check /admin/db-scheduler, and its logs at debug level"
    for _ in $(seq 1 30); do
        found=$(docker compose exec -T postgres psql -U elysium -d elysium -tAc \
            "SELECT count(*) FROM follow f JOIN actor a ON a.id IN (f.follower_id, f.followed_id)
             WHERE a.domain = '$server' AND a.username = '$username'")
        if [[ $found -eq 0 ]]; then
            echo "Elysium deleted $username@$server, with its follows"
            return
        fi
        sleep 2
    done
    fail "Elysium deleted $username@$server, but still has $found of its follows"
}

# Passing means that Elysium stored the Follow, so the account is one whose deletion it has to hear about.
expect_follower() {
    local server=$1 username=$2 found
    echo "Waiting for $username@$server to follow alice"
    for _ in $(seq 1 60); do
        found=$(docker compose exec -T postgres psql -U elysium -d elysium -tAc \
            "SELECT count(*) FROM follow f JOIN actor a ON a.id = f.follower_id
             WHERE f.state = 'accepted' AND a.domain = '$server' AND a.username = '$username'")
        [[ $found -eq 1 ]] && return
        sleep 2
    done
    fail "$username@$server never followed alice. Check Elysium's failed jobs and logs"
}

# Passing means that Elysium took the other server's Update of the lab user, and fetched its actor again. This runs while alice
# follows the lab user, because servers send these to followers.
rename_lab() {
    local domain=$1 before found
    before=$(docker compose exec -T postgres psql -U elysium -d elysium -tAc \
        "SELECT updated_at FROM actor WHERE domain = '$domain' AND username = 'lab'")
    [[ -n $before ]] || fail "Elysium has no lab@$domain"
    case "$domain" in
        mastodon.test)
            docker compose exec -T mastodon-web bin/rails runner '
                lab = Account.find_local("lab")
                UpdateAccountService.new.call(lab, { display_name: "Renamed lab" })
                ActivityPub::UpdateDistributionWorker.new.perform(lab.id)'
            ;;
        gts.test)
            driver curl -fsS -o /dev/null -X PATCH https://gts.test/api/v1/accounts/update_credentials \
                -H "Authorization: Bearer $token" --data-urlencode 'display_name=Renamed lab'
            ;;
        misskey.test)
            misskey_api i/update '{"name": "Renamed lab"}' >/dev/null
            ;;
    esac
    echo "Waiting for Elysium to fetch lab@$domain again"
    for _ in $(seq 1 60); do
        found=$(docker compose exec -T postgres psql -U elysium -d elysium -tAc \
            "SELECT count(*) FROM actor WHERE domain = '$domain' AND username = 'lab' AND updated_at > '$before'")
        if [[ $found -eq 1 ]]; then
            echo "Elysium fetched lab@$domain again after its Update"
            return
        fi
        sleep 2
    done
    fail "Elysium didn't fetch lab@$domain again after its Update. Check /admin/db-scheduler, and its logs at debug level"
}

# Passing means that Elysium took the other server's Update of its status that mentions alice.
expect_status_edited() {
    local server=$1 uri=$2 found
    echo "Waiting for Elysium to edit $uri"
    for _ in $(seq 1 60); do
        found=$(docker compose exec -T postgres psql -U elysium -d elysium -tAc \
            "SELECT count(*) FROM status WHERE uri = '$uri' AND text LIKE '%Edited in the lab%' AND edited_at IS NOT NULL")
        if [[ $found -eq 1 ]]; then
            echo "Elysium edited lab@$server's status"
            return
        fi
        sleep 2
    done
    fail "Elysium didn't edit $uri after $server did. Check /admin/db-scheduler, and its logs at debug level"
}

edit_from_mastodon() {
    local uri
    uri=$(mentioned_status_uri mastodon.test)
    [[ -n $uri ]] || fail "Elysium has no status from lab@mastodon.test to edit"
    docker compose exec -T mastodon-web bin/rails runner '
        lab = Account.find_local("lab")
        lab.statuses.where("text LIKE ?", "%Hello from the lab%").each do |status|
            UpdateStatusService.new.call(status, lab.id, text: "@alice@elysium.test Hello from the lab. Edited in the lab")
        end'
    expect_status_edited mastodon.test "$uri"
}

# Prints the GoToSocial ID of the lab user's status at a URI.
gotosocial_status_id() {
    local uri=$1 lab_id
    lab_id=$(driver curl -fsS https://gts.test/api/v1/accounts/verify_credentials -H "Authorization: Bearer $token" | jq -r .id)
    driver curl -fsS "https://gts.test/api/v1/accounts/$lab_id/statuses?limit=40" \
        -H "Authorization: Bearer $token" | jq -r --arg uri "$uri" '.[] | select(.uri == $uri) | .id'
}

edit_from_gotosocial() {
    local uri status_id
    uri=$(mentioned_status_uri gts.test)
    [[ -n $uri ]] || fail "Elysium has no status from lab@gts.test to edit"
    status_id=$(gotosocial_status_id "$uri")
    [[ -n $status_id ]] || fail "GoToSocial has no status at $uri"
    driver curl -fsS -o /dev/null -X PUT "https://gts.test/api/v1/statuses/$status_id" -H "Authorization: Bearer $token" \
        --data-urlencode 'status=@alice@elysium.test Hello from the lab. Edited in the lab'
    expect_status_edited gts.test "$uri"
}

delete_from_mastodon() {
    local uri
    uri=$(mentioned_status_uri mastodon.test)
    [[ -n $uri ]] || fail "Elysium has no status from lab@mastodon.test to delete"
    docker compose exec -T mastodon-web bin/rails runner '
        Account.find_local("lab").statuses.where("text LIKE ?", "%Hello from the lab%").each do |status|
            RemoveStatusService.new.call(status)
        end'
    expect_status_removed mastodon.test "$uri"
}

delete_from_gotosocial() {
    local uri status_id
    uri=$(mentioned_status_uri gts.test)
    [[ -n $uri ]] || fail "Elysium has no status from lab@gts.test to delete"
    status_id=$(gotosocial_status_id "$uri")
    [[ -n $status_id ]] || fail "GoToSocial has no status at $uri"
    driver curl -fsS -o /dev/null -X DELETE "https://gts.test/api/v1/statuses/$status_id" -H "Authorization: Bearer $token"
    expect_status_removed gts.test "$uri"
}

delete_from_misskey() {
    local uri lab_id note_id
    uri=$(mentioned_status_uri misskey.test)
    [[ -n $uri ]] || fail "Elysium has no status from lab@misskey.test to delete"
    lab_id=$(misskey_api i | jq -r .id)
    # Misskey's notes have no uri of their own, and Elysium stores them at /notes/{id}.
    note_id=${uri##*/notes/}
    misskey_api notes/show "$(jq -cn --arg id "$note_id" '{noteId: $id}')" | jq -e --arg id "$lab_id" '.userId == $id' \
        >/dev/null || fail "Misskey has no note of lab's at $uri"
    misskey_api notes/delete "$(jq -cn --arg id "$note_id" '{noteId: $id}')" >/dev/null
    expect_status_removed misskey.test "$uri"
}

# Likes use the same local API for all peers. The peer-specific helpers exercise their real federation implementation.
expect_elysium_likes() {
    local uri=$1 expected=$2 read_token=$3 body
    for _ in $(seq 1 60); do
        body=$(driver curl -fsS "https://elysium.test/api/v1/statuses/${uri##*/}" -H "Authorization: Bearer $read_token")
        [[ $(jq -r .favourites_count <<<"$body") == "$expected" ]] && return
        sleep 2
    done
    fail "Elysium didn't show $expected likes of $uri: $body"
}

like_from_peer() {
    local uri=$1 action=$2 id output
    case "$target" in
        mastodon)
            output=$(docker compose exec -T -e STATUS_URI="$uri" -e LIKE_ACTION="$action" mastodon-web bin/rails runner '
                lab = Account.find_local("lab")
                status = Status.find_by!(uri: ENV["STATUS_URI"])
                if ENV["LIKE_ACTION"] == "favourite"
                    FavouriteService.new.call(lab, status)
                else
                    UnfavouriteService.new.call(lab, status)
                end
                puts "DONE"')
            echo "$output"
            grep -qxF DONE <<<"$output" || fail "Mastodon couldn't $action $uri"
            ;;
        gotosocial)
            id=$(driver curl -fsS "https://gts.test/api/v1/accounts/$alice_id/statuses" \
                -H "Authorization: Bearer $token" | jq -r --arg uri "$uri" '.[] | select(.uri == $uri) | .id')
            [[ -n $id ]] || fail "GoToSocial has no stored status at $uri"
            driver curl -fsS -o /dev/null -X POST "https://gts.test/api/v1/statuses/$id/$action" \
                -H "Authorization: Bearer $token"
            ;;
        misskey)
            id=$(misskey_api users/notes "$(misskey_user_body)" | jq -r --arg uri "$uri" '.[] | select(.uri == $uri) | .id')
            [[ -n $id ]] || fail "Misskey has no stored note at $uri"
            if [[ $action == favourite ]]; then
                misskey_api notes/reactions/create "$(jq -cn --arg id "$id" '{noteId: $id, reaction: "👍"}')" >/dev/null
            else
                misskey_api notes/reactions/delete "$(jq -cn --arg id "$id" '{noteId: $id}')" >/dev/null
            fi
            ;;
    esac
}

expect_peer_likes() {
    local uri=$1 expected=$2 id body count output
    case "$target" in
        mastodon)
            output=$(docker compose exec -T -e STATUS_URI="$uri" -e EXPECTED="$expected" mastodon-web bin/rails runner '
                matched = false
                60.times do
                    status = Status.uncached { Status.find_by(id: ENV["STATUS_URI"].split("/").last) }
                    matched = status && Favourite.uncached { Favourite.where(status: status).count } == ENV["EXPECTED"].to_i
                    break if matched
                    sleep 2
                end
                puts "LIKES #{matched}"')
            echo "$output"
            grep -qxF 'LIKES true' <<<"$output" || fail "Mastodon didn't show $expected likes of $uri"
            ;;
        gotosocial)
            id=$(gotosocial_status_id "$uri")
            [[ -n $id ]] || fail "GoToSocial has no lab status at $uri"
            for _ in $(seq 1 60); do
                body=$(driver curl -fsS "https://gts.test/api/v1/statuses/$id" -H "Authorization: Bearer $token")
                [[ $(jq -r .favourites_count <<<"$body") == "$expected" ]] && return
                sleep 2
            done
            fail "GoToSocial didn't show $expected likes of $uri: $body"
            ;;
        misskey)
            id=${uri##*/notes/}
            for _ in $(seq 1 60); do
                body=$(misskey_api notes/show "$(jq -cn --arg id "$id" '{noteId: $id}')")
                count=$(jq '[.reactions[]] | add // 0' <<<"$body")
                [[ $count == "$expected" ]] && return
                sleep 2
            done
            fail "Misskey didn't show $expected reactions to $uri: $body"
            ;;
    esac
}

# Run while both the mentioned remote post and alice's public and private posts still exist.
check_likes() {
    local server=$1 remote_uri remote_id read_token write_token body collection uri
    read_token=$(driver sh /federation/elysium-token.sh alice "$password" read)
    write_token=$(driver sh /federation/elysium-token.sh alice "$password" write:favourites)
    remote_uri=$(mentioned_status_uri "$server")
    [[ -n $remote_uri ]] || fail "No remote lab post to favourite"
    remote_id=$(docker compose exec -T postgres psql -U elysium -d elysium -tAc \
        "SELECT id FROM status WHERE uri = '$remote_uri'")
    body=$(driver curl -fsS -X POST "https://elysium.test/api/v1/statuses/$remote_id/favourite" \
        -H "Authorization: Bearer $write_token")
    jq -e '.favourited and .favourites_count == 1' <<<"$body" >/dev/null || fail "Favourite failed: $body"
    expect_peer_likes "$remote_uri" 1
    body=$(driver curl -fsS -X POST "https://elysium.test/api/v1/statuses/$remote_id/unfavourite" \
        -H "Authorization: Bearer $write_token")
    jq -e '(.favourited | not) and .favourites_count == 0' <<<"$body" >/dev/null || fail "Unfavourite failed: $body"
    expect_peer_likes "$remote_uri" 0

    for uri in "$public_uri" "$private_uri"; do
        like_from_peer "$uri" favourite
        expect_elysium_likes "$uri" 1 "$read_token"
        for _ in $(seq 1 30); do
            body=$(driver curl -fsS "https://elysium.test/api/v1/notifications?types%5B%5D=favourite" \
                -H "Authorization: Bearer $read_token")
            jq -e --arg uri "$uri" 'any(.[]; .status.uri == $uri)' <<<"$body" >/dev/null && break
            sleep 2
        done
        jq -e --arg uri "$uri" 'any(.[]; .status.uri == $uri)' <<<"$body" >/dev/null || fail "No favourite notification for $uri"
        if [[ $uri == "$public_uri" ]]; then
            collection=$(driver curl -fsS -H 'Accept: application/activity+json' "$uri/likes?page=true")
            jq -e --arg uri "$uri" '.totalItems == 1 and .orderedItems[0].type == "Like" and .orderedItems[0].object == $uri' \
                <<<"$collection" >/dev/null || fail "Unexpected likes collection: $collection"
        fi
        like_from_peer "$uri" unfavourite
        expect_elysium_likes "$uri" 0 "$read_token"
    done
    echo "$target and Elysium exchanged signed Likes and Undos, with counts, notifications, and a public likes collection"
}

expect_elysium_boosts() {
    local uri=$1 expected=$2 read_token=$3 body
    for _ in $(seq 1 60); do
        body=$(driver curl -fsS "https://elysium.test/api/v1/statuses/${uri##*/}" -H "Authorization: Bearer $read_token")
        [[ $(jq -r .reblogs_count <<<"$body") == "$expected" ]] && return
        sleep 2
    done
    fail "Elysium didn't show $expected boosts of $uri: $body"
}

boost_from_peer() {
    local uri=$1 action=$2 id output
    case "$target" in
        mastodon)
            output=$(docker compose exec -T -e STATUS_URI="$uri" -e BOOST_ACTION="$action" mastodon-web bin/rails runner '
                lab = Account.find_local("lab")
                status = Status.find_by!(uri: ENV["STATUS_URI"])
                if ENV["BOOST_ACTION"] == "reblog"
                    ReblogService.new.call(lab, status)
                else
                    RemoveStatusService.new.call(lab.statuses.find_by!(reblog_of_id: status.id))
                end
                puts "DONE"')
            echo "$output"
            grep -qxF DONE <<<"$output" || fail "Mastodon couldn't $action $uri"
            ;;
        gotosocial)
            id=$(driver curl -fsS "https://gts.test/api/v1/accounts/$alice_id/statuses" \
                -H "Authorization: Bearer $token" | jq -r --arg uri "$uri" '.[] | select(.uri == $uri) | .id')
            [[ -n $id ]] || fail "GoToSocial has no stored status at $uri"
            driver curl -fsS -o /dev/null -X POST "https://gts.test/api/v1/statuses/$id/$action" \
                -H "Authorization: Bearer $token"
            ;;
        misskey)
            id=$(misskey_api users/notes "$(misskey_user_body)" | jq -r --arg uri "$uri" '.[] | select(.uri == $uri) | .id')
            [[ -n $id ]] || fail "Misskey has no stored note at $uri"
            if [[ $action == reblog ]]; then
                misskey_api notes/create "$(jq -cn --arg id "$id" '{renoteId: $id}')" >/dev/null
            else
                misskey_api notes/unrenote "$(jq -cn --arg id "$id" '{noteId: $id}')" >/dev/null
            fi
            ;;
    esac
}

expect_peer_boosts() {
    local uri=$1 expected=$2 id body output
    case "$target" in
        mastodon)
            output=$(docker compose exec -T -e STATUS_URI="$uri" -e EXPECTED="$expected" mastodon-web bin/rails runner '
                matched = false
                60.times do
                    id = ENV["STATUS_URI"].split("/").last
                    matched = Status.uncached { Status.where(reblog_of_id: id).count } == ENV["EXPECTED"].to_i
                    break if matched
                    sleep 2
                end
                puts "BOOSTS #{matched}"')
            echo "$output"
            grep -qxF 'BOOSTS true' <<<"$output" || fail "Mastodon didn't show $expected boosts of $uri"
            ;;
        gotosocial)
            id=$(gotosocial_status_id "$uri")
            [[ -n $id ]] || fail "GoToSocial has no lab status at $uri"
            for _ in $(seq 1 60); do
                body=$(driver curl -fsS "https://gts.test/api/v1/statuses/$id" -H "Authorization: Bearer $token")
                [[ $(jq -r .reblogs_count <<<"$body") == "$expected" ]] && return
                sleep 2
            done
            fail "GoToSocial didn't show $expected boosts of $uri: $body"
            ;;
        misskey)
            # Misskey 2026.10.1 removes a remote renote on its Undo without lowering the note's renoteCount, so this counts
            # the renotes themselves.
            id=${uri##*/notes/}
            for _ in $(seq 1 60); do
                body=$(misskey_api notes/renotes "$(jq -cn --arg id "$id" '{noteId: $id}')")
                [[ $(jq length <<<"$body") == "$expected" ]] && return
                sleep 2
            done
            fail "Misskey didn't show $expected renotes of $uri: $body"
            ;;
    esac
}

# Run while both the mentioned remote post and alice's public post still exist.
check_boosts() {
    local server=$1 remote_uri remote_id read_token write_token body collection
    read_token=$(driver sh /federation/elysium-token.sh alice "$password" read)
    write_token=$(driver sh /federation/elysium-token.sh alice "$password" write:statuses)
    remote_uri=$(mentioned_status_uri "$server")
    [[ -n $remote_uri ]] || fail "No remote lab post to boost"
    remote_id=$(docker compose exec -T postgres psql -U elysium -d elysium -tAc \
        "SELECT id FROM status WHERE uri = '$remote_uri'")
    body=$(driver curl -fsS -X POST "https://elysium.test/api/v1/statuses/$remote_id/reblog" \
        -H "Authorization: Bearer $write_token")
    jq -e '.reblog.reblogged and .reblog.reblogs_count == 1' <<<"$body" >/dev/null || fail "Boost failed: $body"
    expect_peer_boosts "$remote_uri" 1
    body=$(driver curl -fsS -X POST "https://elysium.test/api/v1/statuses/$remote_id/unreblog" \
        -H "Authorization: Bearer $write_token")
    jq -e '(.reblogged | not) and .reblogs_count == 0' <<<"$body" >/dev/null || fail "Unboost failed: $body"
    expect_peer_boosts "$remote_uri" 0

    boost_from_peer "$public_uri" reblog
    expect_elysium_boosts "$public_uri" 1 "$read_token"
    for _ in $(seq 1 30); do
        body=$(driver curl -fsS "https://elysium.test/api/v1/notifications?types%5B%5D=reblog" \
            -H "Authorization: Bearer $read_token")
        jq -e --arg uri "$public_uri" 'any(.[]; .status.uri == $uri)' <<<"$body" >/dev/null && break
        sleep 2
    done
    jq -e --arg uri "$public_uri" 'any(.[]; .status.uri == $uri)' <<<"$body" >/dev/null \
        || fail "No reblog notification for $public_uri"
    collection=$(driver curl -fsS -H 'Accept: application/activity+json' "$public_uri/shares?page=true")
    jq -e --arg uri "$public_uri" '.totalItems == 1 and .orderedItems[0].type == "Announce" and .orderedItems[0].object == $uri' \
        <<<"$collection" >/dev/null || fail "Unexpected shares collection: $collection"
    boost_from_peer "$public_uri" unreblog
    expect_elysium_boosts "$public_uri" 0 "$read_token"
    echo "$target and Elysium exchanged signed Announces and Undos, with counts, notifications, and a shares collection"
}

# Passing means that the other server read manuallyApprovesFollowers from the Update that locking sends to followers, that
# Elysium held the lab user's next Follow without answering it, and that the other server took the Reject and, for the request
# after it, the Accept that alice sent once she decided. The lab user follows alice again at the end, and alice is unlocked,
# for the steps after this.
check_locked() {
    local domain=$1 read_token write_token lab_id activity requests sent
    read_token=$(driver sh /federation/elysium-token.sh alice "$password" read)
    write_token=$(driver sh /federation/elysium-token.sh alice "$password" write)
    [[ -n $read_token && -n $write_token ]] || fail "Couldn't get Elysium access tokens"
    lab_id=$(driver curl -fsS -G https://elysium.test/api/v1/accounts/lookup \
        -H "Authorization: Bearer $read_token" --data-urlencode "acct=lab@$domain" | jq -r .id)

    set_locked true "$write_token"
    "${target}_expect_alice_locked" true
    echo "$target shows alice as locked"

    "${target}_unfollow_alice"
    expect_followers "$domain" 0
    "${target}_request_follow"
    activity=$(expect_follow_request "$domain")
    requests=$(driver curl -fsS https://elysium.test/api/v1/follow_requests -H "Authorization: Bearer $read_token")
    jq -e --arg id "$lab_id" 'map(.id) == [$id]' <<<"$requests" >/dev/null \
        || fail "alice's follow requests don't list lab@$domain: $requests"
    "${target}_expect_relation" requested
    sent=$(docker compose exec -T postgres psql -U elysium -d elysium -tAc \
        "SELECT count(*) FROM outbound_activity WHERE body LIKE '%\"type\":\"Accept\"%' AND body LIKE '%$activity%'")
    [[ $sent -eq 0 ]] || fail "Elysium sent an Accept for lab@$domain's request before alice approved it"
    echo "Elysium holds lab@$domain's request, unanswered, and $target shows it as requested"

    driver curl -fsS -o /dev/null -X POST "https://elysium.test/api/v1/follow_requests/$lab_id/reject" \
        -H "Authorization: Bearer $write_token"
    expect_delivered "$expected_actor#rejects/follows/%"
    "${target}_expect_relation" none
    echo "$target took alice's Reject"

    "${target}_request_follow"
    expect_follow_request "$domain" >/dev/null
    driver curl -fsS -o /dev/null -X POST "https://elysium.test/api/v1/follow_requests/$lab_id/authorize" \
        -H "Authorization: Bearer $write_token"
    "${target}_expect_relation" following
    expect_followers "$domain" 1
    echo "$target took the Accept that alice sent when she approved lab@$domain"

    set_locked false "$write_token"
    "${target}_expect_alice_locked" false
}

# Passing means that the other server took alice's Block of the lab user, along with the Reject that ends the lab user's
# follow of her, and her Undo of the Block. The lab user follows alice again at the end, for the steps after this.
check_blocks() {
    local domain=$1 read_token write_token lab_id body
    read_token=$(driver sh /federation/elysium-token.sh alice "$password" read)
    write_token=$(driver sh /federation/elysium-token.sh alice "$password" write)
    [[ -n $read_token && -n $write_token ]] || fail "Couldn't get Elysium access tokens"
    lab_id=$(driver curl -fsS -G https://elysium.test/api/v1/accounts/lookup \
        -H "Authorization: Bearer $read_token" --data-urlencode "acct=lab@$domain" | jq -r .id)

    body=$(driver curl -fsS -X POST "https://elysium.test/api/v1/accounts/$lab_id/block" \
        -H "Authorization: Bearer $write_token")
    jq -e '.blocking and (.followed_by | not)' <<<"$body" >/dev/null || fail "Block failed: $body"
    expect_followers "$domain" 0
    expect_delivered "$expected_actor#blocks/%"
    "${target}_expect_relation" none
    "${target}_expect_blocked" true
    echo "$target took alice's Block, and ended the lab user's follow of her"

    body=$(driver curl -fsS -X POST "https://elysium.test/api/v1/accounts/$lab_id/unblock" \
        -H "Authorization: Bearer $write_token")
    jq -e '.blocking | not' <<<"$body" >/dev/null || fail "Unblock failed: $body"
    expect_delivered "$expected_actor#blocks/%/undo"
    "${target}_expect_blocked" false
    echo "$target took alice's Undo of her Block"

    "${target}_request_follow"
    "${target}_expect_relation" following
    expect_followers "$domain" 1
}

# Inside one process, as in mastodon_follow.
mastodon_expect_blocked() {
    local output
    output=$(docker compose exec -T -e EXPECTED="$1" mastodon-web bin/rails runner '
        lab = Account.find_local("lab")
        alice = Account.find_by!(username: "alice", domain: "elysium.test")
        blocked = -> { Block.uncached { Block.exists?(account: alice, target_account: lab).to_s } }
        60.times do
            break if blocked.call == ENV["EXPECTED"]
            sleep 2
        end
        puts "BLOCKED #{blocked.call}"')
    grep -qxF "BLOCKED $1" <<<"$output" || fail "Mastodon shows alice blocking lab as: $output, not $1"
}

gotosocial_blocked() {
    driver curl -fsS -G https://gts.test/api/v1/accounts/relationships \
        -H "Authorization: Bearer $token" --data-urlencode "id[]=$alice_id" | jq -r '.[0].blocked_by'
}

gotosocial_expect_blocked() {
    expect_peer "whether alice blocks lab" "$1" gotosocial_blocked
}

misskey_blocked() {
    misskey_api users/relation "$(misskey_user_body)" | jq -r '.[0].isBlocked'
}

misskey_expect_blocked() {
    expect_peer "whether alice blocks lab" "$1" misskey_blocked
}

set_locked() {
    local locked=$1 write_token=$2 actor
    driver curl -fsS -o /dev/null -X PATCH https://elysium.test/api/v1/accounts/update_credentials \
        -H "Authorization: Bearer $write_token" --data-urlencode "locked=$locked"
    actor=$(driver curl -fsS -H 'Accept: application/activity+json' "$expected_actor")
    [[ $(jq -r .manuallyApprovesFollowers <<<"$actor") == "$locked" ]] \
        || fail "alice's actor document doesn't have manuallyApprovesFollowers $locked: $actor"
    expect_delivered "$expected_actor#profile/%"
}

# Prints the id of the Follow behind the waiting request, once there is one.
expect_follow_request() {
    local server=$1 found
    echo "Waiting for Elysium to hold a request from $server" >&2
    for _ in $(seq 1 60); do
        found=$(docker compose exec -T postgres psql -U elysium -d elysium -tAc \
            "SELECT f.activity_uri FROM follow f JOIN actor a ON a.id = f.follower_id
             WHERE f.state = 'pending' AND a.domain = '$server'")
        if [[ -n $found ]]; then
            echo "$found"
            return
        fi
        sleep 2
    done
    fail "Elysium never held a request from $server. Check its failed jobs and logs"
}

mastodon_unfollow_alice() {
    docker compose exec -T mastodon-web bin/rails runner '
        UnfollowService.new.call(Account.find_local("lab"), ResolveAccountService.new.call("alice@elysium.test"))'
}

mastodon_request_follow() {
    docker compose exec -T mastodon-web bin/rails runner '
        FollowService.new.call(Account.find_local("lab"), ResolveAccountService.new.call("alice@elysium.test"))'
}

# Inside one process, as in mastodon_follow.
mastodon_expect_relation() {
    local output
    output=$(docker compose exec -T -e EXPECTED="$1" mastodon-web bin/rails runner '
        lab = Account.find_local("lab")
        alice = Account.find_by!(username: "alice", domain: "elysium.test")
        relation = -> {
            Follow.uncached do
                if Follow.exists?(account: lab, target_account: alice) then "following"
                elsif FollowRequest.exists?(account: lab, target_account: alice) then "requested"
                else "none" end
            end
        }
        60.times do
            break if relation.call == ENV["EXPECTED"]
            sleep 2
        end
        puts "RELATION #{relation.call}"')
    grep -qxF "RELATION $1" <<<"$output" || fail "Mastodon shows lab's relationship to alice as: $output, not $1"
}

mastodon_expect_alice_locked() {
    local output
    output=$(docker compose exec -T -e EXPECTED="$1" mastodon-web bin/rails runner '
        locked = -> { Account.uncached { Account.find_by!(username: "alice", domain: "elysium.test").locked.to_s } }
        60.times do
            break if locked.call == ENV["EXPECTED"]
            sleep 2
        end
        puts "LOCKED #{locked.call}"')
    grep -qxF "LOCKED $1" <<<"$output" || fail "Mastodon shows alice's locked as: $output, not $1"
}

gotosocial_unfollow_alice() {
    driver curl -fsS -o /dev/null -X POST "https://gts.test/api/v1/accounts/$alice_id/unfollow" \
        -H "Authorization: Bearer $token"
}

gotosocial_request_follow() {
    driver curl -fsS -o /dev/null -X POST "https://gts.test/api/v1/accounts/$alice_id/follow" \
        -H "Authorization: Bearer $token"
}

gotosocial_relation() {
    driver curl -fsS -G https://gts.test/api/v1/accounts/relationships \
        -H "Authorization: Bearer $token" --data-urlencode "id[]=$alice_id" \
        | jq -r '.[0] | if .following then "following" elif .requested then "requested" else "none" end'
}

gotosocial_expect_relation() {
    expect_peer "lab's relationship to alice" "$1" gotosocial_relation
}

gotosocial_alice_locked() {
    driver curl -fsS "https://gts.test/api/v1/accounts/$alice_id" -H "Authorization: Bearer $token" | jq -r .locked
}

gotosocial_expect_alice_locked() {
    expect_peer "alice's locked" "$1" gotosocial_alice_locked
}

misskey_unfollow_alice() {
    misskey_api following/delete "$(misskey_user_body)" >/dev/null
}

misskey_request_follow() {
    misskey_api following/create "$(misskey_user_body)" >/dev/null
}

misskey_relation() {
    misskey_api users/relation "$(misskey_user_body)" \
        | jq -r '.[0] | if .isFollowing then "following" elif .hasPendingFollowRequestFromYou then "requested" else "none" end'
}

misskey_expect_relation() {
    expect_peer "lab's relationship to alice" "$1" misskey_relation
}

misskey_alice_locked() {
    misskey_api users/show "$(misskey_user_body)" | jq -r .isLocked
}

misskey_expect_alice_locked() {
    expect_peer "alice's locked" "$1" misskey_alice_locked
}

# Waits for a function that reads something from the other server to print what's expected.
expect_peer() {
    local what=$1 expected=$2 read=$3 found=''
    echo "Waiting for $target to show $what as $expected"
    for _ in $(seq 1 60); do
        found=$("$read")
        [[ $found == "$expected" ]] && return
        sleep 2
    done
    fail "$target shows $what as $found, not $expected"
}

# These delete a second account, which follows alice, or which she follows, so that its server tells Elysium, rather than the
# lab user, which later steps need, and which Misskey can't delete because it's the admin.
delete_account_from_mastodon() {
    docker compose exec -T mastodon-web bin/tootctl accounts create gone --email gone@mastodon.test --confirmed --approve
    docker compose exec -T mastodon-web bin/rails runner '
        FollowService.new.call(Account.find_local("gone"), ResolveAccountService.new.call("alice@elysium.test"))'
    expect_follower mastodon.test gone
    docker compose exec -T mastodon-web bin/rails runner '
        DeleteAccountService.new.call(Account.find_local("gone"), reserve_username: false)'
    expect_actor_deleted mastodon.test gone
}

# GoToSocial only tells a deleted account's followers, and withdraws its follows first, so here alice follows it instead.
delete_account_from_gotosocial() {
    local gone_token lab_token read_token write_token gone_id following
    gts_admin account create --username gone --email gone@gts.test --password "$password"
    gts_admin account confirm --username gone
    gone_token=$(driver sh /federation/gotosocial-token.sh gone@gts.test "$password")
    [[ -n $gone_token ]] || fail "Couldn't get a GoToSocial access token for gone"
    read_token=$(driver sh /federation/elysium-token.sh alice "$password" read)
    write_token=$(driver sh /federation/elysium-token.sh alice "$password" write)
    gone_id=$(driver curl -fsS -G https://elysium.test/api/v1/accounts/lookup \
        -H "Authorization: Bearer $read_token" --data-urlencode "acct=gone@gts.test" | jq -r .id) \
        || fail "Elysium couldn't find gone@gts.test"
    driver curl -fsS -o /dev/null -X POST "https://elysium.test/api/v1/accounts/$gone_id/follow" \
        -H "Authorization: Bearer $write_token"
    lab_token=$token
    token=$gone_token
    gotosocial_approve
    token=$lab_token
    echo "Waiting for gone@gts.test to accept alice's Follow"
    for _ in $(seq 1 60); do
        following=$(driver curl -fsS -G https://elysium.test/api/v1/accounts/relationships \
            -H "Authorization: Bearer $read_token" --data-urlencode "id[]=$gone_id" | jq -r '.[0].following')
        [[ $following == true ]] && break
        sleep 2
    done
    [[ $following == true ]] || fail "gone@gts.test never accepted alice's Follow"
    driver curl -fsS -o /dev/null https://gts.test/api/v1/accounts/delete \
        -H "Authorization: Bearer $gone_token" --data-urlencode "password=$password"
    expect_actor_deleted gts.test gone
}

delete_account_from_misskey() {
    local account gone_token gone_id
    account=$(misskey_api admin/accounts/create "$(jq -cn --arg password "$password" '{username: "gone", password: $password}')")
    gone_token=$(jq -r .token <<<"$account")
    gone_id=$(jq -r .id <<<"$account")
    [[ -n $gone_token && $gone_token != null ]] || fail "Couldn't create Misskey's gone account: $account"
    driver curl -fsS -o /dev/null https://misskey.test/api/following/create -H 'Content-Type: application/json' \
        -d "$(jq -cn --arg token "$gone_token" --arg id "$alice_id" '{i: $token, userId: $id}')"
    expect_follower misskey.test gone
    misskey_api admin/delete-account "$(jq -cn --arg id "$gone_id" '{userId: $id}')" >/dev/null
    expect_actor_deleted misskey.test gone
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
        check_locked gts.test
        check_blocks gts.test
        follow_from_elysium gts.test post_from_gotosocial gotosocial_approve
        edit_from_gotosocial
        delete_account_from_gotosocial
        post_from_elysium
        gotosocial_expect_statuses
        check_likes gts.test
        check_boosts gts.test
        delete_from_gotosocial
        delete_from_elysium
        gotosocial_expect_deleted
        edit_from_elysium
        media_from_elysium
        update_profile_from_elysium
        moderate_from_elysium
        ;;
    mastodon)
        resolve_from_mastodon
        deliver_from_mastodon
        follow_from_mastodon
        check_locked mastodon.test
        check_blocks mastodon.test
        follow_from_elysium mastodon.test post_from_mastodon
        edit_from_mastodon
        media_from_mastodon
        delete_account_from_mastodon
        post_from_elysium
        mastodon_expect_statuses
        check_likes mastodon.test
        check_boosts mastodon.test
        delete_from_mastodon
        delete_from_elysium
        mastodon_expect_deleted
        edit_from_elysium
        media_from_elysium
        update_profile_from_elysium
        moderate_from_elysium
        ;;
    misskey)
        resolve_from_misskey
        deliver_from_misskey
        follow_from_misskey
        check_locked misskey.test
        check_blocks misskey.test
        follow_from_elysium misskey.test post_from_misskey
        delete_account_from_misskey
        post_from_elysium
        misskey_expect_statuses
        check_likes misskey.test
        check_boosts misskey.test
        delete_from_misskey
        delete_from_elysium
        misskey_expect_deleted
        edit_from_elysium
        media_from_elysium
        update_profile_from_elysium
        moderate_from_elysium
        ;;
esac

echo "PASSED: $target can discover alice@elysium.test, deliver to it, follow and unfollow it, have its requests held, refused, and approved while it's locked, take its Block and Undo, be followed and unfollowed by it, send it statuses and reach its home timeline, receive its statuses, their edits, and profile changes, exchange likes and unlikes, and boosts and unboosts, have its boosts of other posts fetched and its older posts backfilled, and accept its suspension and deletion, and Elysium takes $target's profile changes and any status edits, and removes the status and account that $target deleted"
