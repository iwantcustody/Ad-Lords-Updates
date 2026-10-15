# Ad Lords: Android app

The whole game ships inside the app (`app/src/main/assets/www`), so it runs offline.

What the app adds over the browser:
- **Volume buttons as triggers.** During play, hold **volume up** to fire and press **volume down** for a Hellfire or missile. In menus they change the volume as normal.
- **Full screen.** The status and navigation bars are hidden; swipe from an edge to bring them back for a moment.
- **Auto-rotate.** It turns to landscape when you take a gunner or pilot seat and unlocks when you leave.
- **Screen stays on, and sound starts without an extra tap.**
- **Back button** = the game's Esc: pause, or leave a seat.

## Build it in Android Studio (easiest)
1. Install Android Studio (free): https://developer.android.com/studio
2. **File → Open** and pick this folder. Let it sync; the first time it downloads Gradle and the Android SDK.
3. Plug in your phone with **USB debugging** on (Settings → About phone → tap *Build number* 7 times → Developer options → USB debugging).
4. Press the green **Run ▶** button. The app installs and opens on the phone.

To get an APK file instead: **Build → Build App Bundle(s) / APK(s) → Build APK(s)**. It lands in `app/build/outputs/apk/debug/app-debug.apk`; copy that to the phone and open it to install. You may need to allow installs from that app.

## How updates work
- **Ad-Lords** (private) holds the source. **Ad-Lords-Updates** (public) builds and publishes: every push to its `main` runs `.github/workflows/android.yml`, which builds a signed APK and creates a GitHub release with `AdLords.apk` and `update.json`.
- A few seconds after the app starts it reads `releases/latest/download/update.json`. Game files that changed are downloaded in the background and used after you tap **Restart** on the banner (or the next time the app opens). Only the changed files are fetched.
- If the Android part changed (Java, manifest, resources), the banner offers **Install** instead: the app downloads the new APK and opens the Android installer. The first time, Android asks you to allow installs from Ad Lords.
- Every build is signed with the same key (`app/release.jks`), so new APKs install over the old one and keep your saves. Keep a copy of that file: an APK signed with a different key can't update the app.
- Settings → Gameplay → **Check now** checks by hand.

## Before the Play Store
Make a separate upload key for the Play Store and upload an **.aab**.
