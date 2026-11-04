#!/bin/sh
# Creates the lab's CA and server certificate. Runs inside the certs service.
set -eu

cd /certs

openssl req -x509 -newkey rsa:2048 -nodes -days 7 \
    -keyout ca.key -out ca.crt -subj '/CN=Elysium federation lab CA'

openssl req -newkey rsa:2048 -nodes \
    -keyout server.key -out server.csr -subj '/CN=elysium.test'

cat > server.ext <<'EOF'
subjectAltName = DNS:elysium.test, DNS:gts.test, DNS:mastodon.test
basicConstraints = CA:FALSE
keyUsage = digitalSignature, keyEncipherment
extendedKeyUsage = serverAuth
EOF

openssl x509 -req -in server.csr -CA ca.crt -CAkey ca.key -CAcreateserial \
    -days 7 -sha256 -extfile server.ext -out server.crt

rm -f ca.key ca.srl server.csr server.ext

# The other services run as other users and need the CA. Only Caddy, as root, needs the key.
chmod 755 /certs
chmod 644 ca.crt server.crt
chmod 600 server.key
