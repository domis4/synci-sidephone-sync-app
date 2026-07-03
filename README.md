# Synci

A small local sync app for my Sidephone.

Synci starts a tiny web server directly on the Android device and lets me move files between my computer and the Sidephone from a browser on the same network.

> This project was absolutely vibe-coded.  
> Built by feeling, iteration, screenshots, and “okay, now make it nicer”.

## Features

- Local HTTP server on the Android device
- Server URL and QR code in the app
- Persistent notification while the server is running
- Browser-based file manager
- Upload via button or drag and drop
- Download and delete from the browser
- Sections for photos, music, documents, contacts, and other files
- Photo previews
- Music metadata and album art where available

## Screenshots

### Android app

![Synci start screen](screenshots/screen-start-screen.png)

![Synci running server screen](screenshots/screen-running-screen.png)

![Synci foreground service notification](screenshots/screen-service-notification.png)

### Web UI

![Synci web music view](screenshots/web-music.png)

![Synci web photos view](screenshots/web-photos.png)

## Design

Synci uses a dark, glossy, slightly skeuomorphic interface.  
The Android app and web UI are intentionally matched with rounded panels, inset surfaces, and shiny action buttons.

## Security note

Synci is intended for trusted local networks only.

Do not expose it to the internet or use it as a public file server.

## Development

Build and install:

```bash
./gradlew clean assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell monkey -p com.wnderlvst.sidephonesync 1

## LICENSE

MIT
