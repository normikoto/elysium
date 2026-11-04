#!/bin/sh
# Creates the lab's CA and server certificate. Runs inside the certs service.
set -eu

cd /certs

# Compose runs this again for each `docker compose run elysium`, and a new CA wouldn't match what Caddy has already loaded.
# The type file is written last, so a run that didn't finish starts again. These certificates only last 7 days, so a lab
# kept for longer needs new ones, and `docker compose restart caddy elysium` to load them.
if [ -f bindings/lab-ca/type ] && openssl x509 -checkend 86400 -noout -in server.crt > /dev/null; then
    exit 0
fi

openssl req -x509 -newkey rsa:2048 -nodes -days 7 \
    -keyout ca.key -out ca.crt -subj '/CN=Elysium federation lab CA'

openssl req -newkey rsa:2048 -nodes \
    -keyout server.key -out server.csr -subj '/CN=elysium.test'

cat > server.ext <<'EOF'
subjectAltName = DNS:elysium.test, DNS:gts.test, DNS:mastodon.test, DNS:misskey.test
basicConstraints = CA:FALSE
keyUsage = digitalSignature, keyEncipherment
extendedKeyUsage = serverAuth
EOF

openssl x509 -req -in server.csr -CA ca.crt -CAkey ca.key -CAcreateserial \
    -days 7 -sha256 -extfile server.ext -out server.crt

rm -f ca.key ca.srl server.csr server.ext

# How the Elysium image's JVM gets to trust the CA: its buildpack adds every file in a binding of this type to the JVM's
# trust store at startup. This has its own directory, so that the binding only holds the CA.
mkdir -p bindings/lab-ca
cp ca.crt bindings/lab-ca/ca.crt

# The other services run as other users and need the CA. Only Caddy, as root, needs the key.
chmod 755 /certs bindings bindings/lab-ca
chmod 644 ca.crt server.crt bindings/lab-ca/ca.crt
chmod 600 server.key

printf 'ca-certificates' > bindings/lab-ca/type
chmod 644 bindings/lab-ca/type
