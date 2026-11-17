# Screen OCR (English + اردو)

Floating-button Android app that reads text from whatever is on your screen, in any app or website, and shows it in an editable, copyable window.

## Features
- Floating draggable button that stays on top of other apps (no need to open the app each time)
- **Full screen** capture or **Select area** (drag a rectangle) with one tap
- No screenshots to upload: the screen is captured live through Android's screen-capture API
- Offline OCR with Tesseract LSTM, English + Urdu (`eng` + `urd`)
- Pre-processing for accuracy: grayscale, dark-mode inversion, upscaling of small selections
- Text analysis: Unicode clean-up, whitespace repair, word/character/line count, script detection, "Join lines" reflow
- Editable result window with Copy, Share; optional auto-copy
- Also: pick any image file and extract its text

## Build the APK (no Android Studio needed)
1. Create a free GitHub account and a new repository.
2. Upload the whole contents of this folder to it (keep the `.github` folder).
3. Open the repository's **Actions** tab, choose **Build APK**, press **Run workflow**.
4. After ~5-8 minutes, open the finished run and download the **ScreenOCR-apk** artifact. Unzip it, then install `app-debug.apk` on your phone (allow "Install unknown apps" when asked).

### Or with Android Studio
Open this folder in Android Studio (Jellyfish or newer, JDK 17) and press Run, or run `./gradlew assembleDebug`. The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## First-time setup on the phone
1. Open **Screen OCR**, tap **Start floating button**.
2. Allow **Display over other apps** (opens Android settings), return and tap Start again.
3. Allow notifications (needed for the "running" notice).
4. On the screen-sharing prompt choose **Entire screen** (not "a single app") and tap **Start now**.
5. Switch to any app: tap the **OCR** bubble, then **Full screen** or **Select area**.

## Known limits
- Apps that block screen capture (banking apps, some video apps) show black and give no text. This is an Android security rule.
- Android asks for screen-capture permission every time the service is started; that is by design.
- Urdu in Nastaliq style fonts is the hardest case for Tesseract. For best results pick "اردو only" when a page is pure Urdu, and select tight areas.
- Some phones (Xiaomi, Oppo, Vivo, Samsung power saving) kill background apps: set Screen OCR to "No restrictions" / "Autostart" in battery settings.

## Swapping in a better Urdu model
`app/src/main/assets/tessdata/urd.traineddata` comes from `tessdata_best`. `eng.traineddata` comes from `tessdata_fast`. Replace either file with another Tesseract 4/5 LSTM model of the same name to trade speed for accuracy.
