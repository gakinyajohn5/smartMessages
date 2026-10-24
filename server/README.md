# Pesa Board licensing server

Node 18+ and SQLite. Activation codes lock to one phone, and top-up codes work once, on the phone that owns the license.

## Run

    cd server
    npm install
    ADMIN_KEY="a-long-random-secret-16+chars" npm start

Optional env vars: `PORT` (default 3000), `DB_PATH` (default `data.db`), `TRUST_PROXY=1` when behind a host's proxy.
Deploy somewhere with HTTPS and a persistent disk (Render, Railway, Fly.io, a VPS). Back up the `.db` file.

## Admin (you)

All admin calls need the header `x-admin-key: <ADMIN_KEY>`.

    # make 5 activation codes to sell
    curl -X POST $URL/admin/licenses -H "x-admin-key: $KEY" -H "content-type: application/json" \
         -d '{"count":5,"note":"batch 1"}'

    # give a buyer Ksh 25,000 of game money (returns one-time code)
    curl -X POST $URL/admin/topups -H "x-admin-key: $KEY" -H "content-type: application/json" \
         -d '{"licenseCode":"ABCD-EFGH-JKLM","amount":25000}'

    # buyer got a new phone: unlock the license so it can activate again
    curl -X POST $URL/admin/licenses/ABCD-EFGH-JKLM/unbind -H "x-admin-key: $KEY"

    # revoke a license
    curl -X POST $URL/admin/licenses/ABCD-EFGH-JKLM/revoke -H "x-admin-key: $KEY"

    # see everything
    curl $URL/admin/licenses -H "x-admin-key: $KEY"

## App

Set your server address in `app/build.gradle.kts` (`API_BASE_URL`, https only), then build.

## Limits worth knowing

- The device lock uses Android's `ANDROID_ID`, which changes after a factory reset. Use `unbind` for genuine requests.
- Activation is remembered on the phone, so a rooted device could bypass the check. A determined attacker can modify any APK. Top-ups are the part that's always enforced by the server.
