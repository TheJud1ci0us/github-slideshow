# Purchase-check server (≈30 minutes, Google Cloud free tier)

This small function checks with Google that a purchase is real and that the request comes from
the genuine LingoPod from Play, then returns a signed "Pro pass". Without it, Pro can't be
trusted, so do this before the first release.

You need: the Play Console account, a Google account, and the files I sent you:
`pro-pass-private-pkcs8.pem` (secret — never share or commit it).

## 1. Google Cloud project
1. Go to console.cloud.google.com → create a project, e.g. **lingopod** (note its **project number**).
2. Enable APIs (APIs & Services → Library): **Google Play Android Developer API**,
   **Play Integrity API**, **Cloud Run Admin API**, **Secret Manager API**, **Cloud Build API**.
3. Billing: Cloud Run needs a billing account attached, but this function stays within the free
   tier (2 million requests a month).

## 2. Service account for Play
1. IAM & Admin → Service accounts → **Create**: name `lingopod-verifier`. No roles needed here.
2. Play Console → **Users and permissions → Invite new users**: the service account's e-mail;
   App permissions: LingoPod; Account permissions: **View app information** and
   **View financial data, orders and cancellation survey responses**. Invite.
3. Play Console → Setup → **App integrity → Play Integrity API**: link the Cloud project.

## 3. The signing key as a secret
In the Cloud console top bar open **Cloud Shell**, upload `pro-pass-private-pkcs8.pem`, then:
```
gcloud secrets create pass-private-key --data-file=pro-pass-private-pkcs8.pem
```

## 4. Deploy
Upload the `server/` folder (or `git clone` the repository) in Cloud Shell, then:
```
cd ascolta-android/server
gcloud run deploy lingopod-pro-check --source . --region europe-west1 \
  --service-account lingopod-verifier@<PROJECT_ID>.iam.gserviceaccount.com \
  --set-env-vars PACKAGE_NAME=app.lingopod,PASS_DAYS=7,GOOGLE_FUNCTION_TARGET=verify \
  --set-secrets PASS_PRIVATE_KEY=pass-private-key:latest --allow-unauthenticated
```
It prints a URL like `https://lingopod-pro-check-xxxx.a.run.app`.

## 5. Tell the app
In `ascolta-android/gradle.properties` set (then I build the release):
```
lingopodServerUrl=https://lingopod-pro-check-xxxx.a.run.app
lingopodCloudProjectNumber=123456789012
```
