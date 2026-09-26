# Zip to GitHub

A tiny Android app that takes a `.zip` file (like the ones I generate for you
in chat) and uploads every file inside it straight into a GitHub repository —
using the GitHub API directly. No drag-and-drop, no "select all" on a folder,
no extracting the zip yourself first.

Build it once (this time, from your laptop, the same way as the last app),
install the APK on your phone, and from then on you can push any future
project's zip straight to GitHub from your phone alone.

## Step 1 — Get this project onto GitHub and build it (one-time, needs a laptop)

Same process as last time:

1. Unzip `ZipToGitHub.zip`.
2. Create a new GitHub repository (e.g. `zip-to-github-app`).
3. Upload the *contents* of the unzipped folder to the repo root (drag them
   all in via "Add file → Upload files", or use `git push` from a terminal).
4. Go to the **Actions** tab, enable workflows if asked, and either wait for
   the automatic run or click **Run workflow**.
5. When it finishes (green check), open the run, scroll to **Artifacts**,
   download **ZipToGitHub-app**, unzip it — you'll get `app-debug.apk`.
6. Copy that APK to your phone and install it (allow "install unknown
   sources" if prompted, same as before).

You only need a laptop for **this** app. After it's installed, you won't
need a laptop again for future projects — just this app + your phone.

## Step 2 — Create a GitHub Personal Access Token

The app needs a token to act on your behalf (this replaces logging in with
a password, which GitHub no longer allows for apps like this).

1. On GitHub (laptop or phone browser), go to
   **Settings → Developer settings → Personal access tokens → Tokens (classic)**.
   Direct link: https://github.com/settings/tokens
2. Tap **Generate new token → Generate new token (classic)**.
3. Give it a name like "zip uploader phone".
4. Set an expiration (30–90 days is a good habit; you can always make a new
   one later).
5. Check the **repo** scope (this gives it permission to read/write your
   repositories — that's all it needs).
6. Tap **Generate token**, then **copy it immediately** — GitHub only shows
   it once. Paste it somewhere safe (or straight into the app).

Keep this token private — anyone with it can act on your repos with the
permissions you granted it. If you ever lose control of your phone, revoke
it from the same Tokens page.

## Step 3 — Use the app

1. Open **Zip to GitHub**.
2. Paste your **token**.
3. Enter your GitHub **username** and the **repository name** you want to
   upload to (it doesn't have to exist yet).
4. Leave **"Create repository if it doesn't exist"** checked if it's a new
   project, and choose **Private** or **Public**.
5. Leave **"Remove the outer folder from the zip"** checked — this is what
   makes it drop the files directly into the repo root, exactly like you
   wanted last time, instead of nesting everything one level deep.
6. Tap **Choose ZIP file** and pick the zip (e.g. one I give you in a future
   chat, saved to your phone from the file card).
7. Tap **Upload to GitHub**. Watch the log — it lists every file as it's
   uploaded. When it finishes, tap **Open Repository** to see it live.

The token, username, and your checkbox choices are remembered for next
time, so future uploads are just: choose zip → tap Upload.

## Notes and limits

- Works over Wi-Fi or mobile data — needs an internet connection.
- The GitHub API used here caps individual files at 1 MB; anything larger
  in the zip is skipped and logged (rare for source-code projects).
- If a file already exists in the repo, it gets updated (not duplicated) —
  so you can re-upload the same zip after making changes.
- The token is stored in this app's private storage on your phone only. On
  a non-rooted phone, other apps can't read it.
