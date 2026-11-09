# Jellyseerr TV

An Android TV / Google TV / Fire TV app for requesting movies and shows from
[Jellyseerr](https://github.com/fallenbagel/jellyseerr) with a remote or a game
controller.

The app shows your own Jellyseerr server (by default
`http://yarr.melhorn.com:5055/`) full screen. It adds a TV navigation layer on
top: a glowing highlight moves between posters, buttons and fields when you
use the D-pad or a controller, so you never need a mouse.

## Controls

| Button | Remote | Game controller |
| --- | --- | --- |
| Move highlight | D-pad | D-pad or left stick |
| Select | OK / Center | A |
| Back, close dialog, hide keyboard | Back | B |
| Search (opens the keyboard) | Search / mic key | Y |
| Go to Discover | – | X |
| Page up / down | Rewind / Fast-forward, Channel +/− | LB / RB |
| Jump to top | – | LT |
| Scroll smoothly | – | Right stick |
| Reload | – | View / Select |
| Settings | Menu, or **hold Back** | Start / Menu |

To request something, highlight a poster, press **OK** to open it, then choose
**Request**. Pressing Back takes you back to the poster you came from.

On a text field, press **OK** to open the on-screen keyboard. Left and right
move the cursor, and up or down leaves the field.

Drop-downs (`<select>`) open as a native TV list picker.

## Settings

Open settings with **Menu**, **Start**, or by **holding Back** for about a second.
Pressing Back on the start page also offers Settings.

- **Server address**: point the app at a different Jellyseerr URL, such as a LAN IP.
- **Display size**: how large Jellyseerr is drawn. *Normal* (1280 px wide)
  gives the desktop layout with the sidebar. Choose *Large* if text is hard
  to read from the couch.
- **Reload page**, **Back to start page**, **Controller help**.
- **Sign out and clear data**: removes cookies and the cached login.

## Getting the APK

Every push runs the **Build APK** GitHub Actions workflow
(`.github/workflows/build.yml`):

- Open the repository's **Actions** tab, select the latest run, and download
  the `JellyseerrTV-apk` artifact. It is a zip that contains `JellyseerrTV.apk`.
- Push a tag such as `v1.0.0` to attach the APK to a GitHub Release.

To build it yourself with Android Studio, or the Android SDK and JDK 17:

```sh
./gradlew assembleRelease
# -> app/build/outputs/apk/release/app-release.apk
```

To bake in a different default server, add
`-PserverUrl=http://192.168.1.10:5055/` to the Gradle command.

## Installing on the TV (sideloading)

1. On the TV, enable **Developer options**: Settings → System → About →
   select *Build* 7 times. Fire TV: Settings → My Fire TV → About → select
   the device name 7 times.
2. Allow installs from the app you will use, such as *Downloader*, *Send Files to
   TV* or a file manager. Or turn on **USB/ADB debugging**.
3. Install the APK:
   - **ADB** from a computer on the same network:
     ```sh
     adb connect <tv-ip>:5555
     adb install -r JellyseerrTV.apk
     ```
   - Or copy the APK with *Send Files to TV* or a USB drive and open it with a
     file manager.
4. **Jellyseerr TV** appears in the apps row of the TV home screen.

Sign in once, using a Jellyfin, Plex or local account. The app remembers the session.

### Updating

Android only installs an update over an existing copy when both APKs are signed
with the same key. The workflow caches its signing key between runs, but GitHub
evicts caches that go unused for 7 days. For a key that never changes, create a
keystore once and add it as repository secrets:

```sh
keytool -genkeypair -v -keystore release.jks -alias jellyseerrtv \
  -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 release.jks   # paste into the SIGNING_KEYSTORE_BASE64 secret
```

Secrets: `SIGNING_KEYSTORE_BASE64`, `SIGNING_KEYSTORE_PASSWORD`,
`SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`.

If an install fails with "App not installed" or a signature conflict,
uninstall the old version first.

## How it works

- `MainActivity.kt` hosts a full-screen WebView. It intercepts remote, gamepad
  and analog-stick input and forwards it to the navigation layer. It also
  handles pop-up windows (Plex sign-in, trailers), full-screen video, the
  connection-error screen and the settings dialogs.
- `assets/tvnav.js` is injected into every page. It does the spatial
  navigation (picks the nearest control in the pressed direction and keeps it
  scrolled into view, including inside the horizontal poster rows). It keeps
  focus inside open dialogs, handles Back for dialogs, menus and text fields,
  shows a poster's hover details when the poster is highlighted, and remembers
  the highlighted item per page. It also sets the page width so Jellyseerr uses
  its desktop layout.
- Cleartext HTTP is allowed, because Jellyseerr is usually served over plain
  HTTP on the home network.
