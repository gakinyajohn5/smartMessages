# Pesa Board licensing on Supabase

## Set up (once)
1. In your Supabase project open **SQL Editor**, paste all of `schema.sql`, and run it.
2. In `app/build.gradle.kts` set `SUPABASE_URL` and `SUPABASE_KEY`
   (Project Settings → API: the project URL and the **anon / publishable** key).
   Never put the `service_role` / secret key in the app.
3. Build the app.

## Run your business from the SQL editor

    -- make 5 activation codes to sell
    select * from private.create_licenses(5, 'batch 1');

    -- give one buyer Ksh 25,000 of game money (returns a one-time code)
    select * from private.create_topups('ABCD-EFGH-JKLM', 25000);

    -- buyer got a new phone: unlock so they can activate again
    select private.unbind('ABCD-EFGH-JKLM');

    -- revoke a license
    select private.revoke_license('ABCD-EFGH-JKLM');

    -- who's activated, how much redeemed / pending
    select * from private.overview;

## How it's locked down
- Tables live in the `private` schema, which the Supabase API doesn't expose, so the app key can't read or change them.
- The phone can only call `activate_license` and `redeem_topup`. Both check the code, the phone's ID, and that a top-up belongs to that license and is unused.
- 10 wrong attempts from one phone in 15 minutes blocks it for a while.

## Limits worth knowing
- The phone lock uses Android's `ANDROID_ID`, which changes after a factory reset. Use `private.unbind` for genuine requests.
- Activation is remembered on the phone, so a rooted device could bypass that check. Top-ups are always enforced by the database.
