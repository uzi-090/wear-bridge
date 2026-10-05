# Third-party assets

## Brand logos (watch app)
- **Source:** [Simple Icons](https://simpleicons.org) `simple-icons@16.34.0`, fetched once at development time from
  `cdn.jsdelivr.net/npm/simple-icons@16.34.0/icons/<slug>.svg` and converted to Android vector drawables
  (`wearos/app/src/main/res/drawable/logo_*.xml`). They're bundled in the app and **never downloaded at runtime**.
- **Licence:** CC0 1.0 for the Simple Icons artwork.
- **Trademarks:** every logo is a trademark of its owner (WhatsApp, Instagram, Telegram, Gmail, etc.).
  Bridge uses them only to show which app a notification came from (nominative use).
  **Before any Play Store / App Store release, get a trademark review or strip them** (delete
  `logo_*.xml` and their `AppLogos` entries; badges fall back to monograms automatically). Risk R-13.
- **Included:** airtel, applemusic, discord, gmail, googlechat, googlemaps, imessage, instagram, messenger,
  signal, spotify, swiggy, telegram, whatsapp, x, youtube.
- **Not available in Simple Icons** (monogram fallback): Flipkart, Truecaller, Kotak 811, Rapido, Ola,
  ZEE5, EazyDiner, Lenskart, GeoGuessr, Slack, LinkedIn, Microsoft Teams/Outlook, FaceTime.

## Media glyphs and sounds
- `ic_play`, `ic_pause`, `ic_skip_next`, `ic_skip_previous`, `ic_volume_up`, `ic_volume_down`, `ic_music_note`:
  Material Icons path data (Apache License 2.0), written as vector XML. Nothing downloaded.
- `ios/PairingPOC/Resources/FindPhone.caf`: an original chime synthesized for this project (three sine
  tones, 29.5 s, IMA4). No third-party audio.

## Generic glyphs
- `glyph_phone.xml`, `glyph_hourglass.xml`: Material Icons ("call", "hourglass_full"), Apache License 2.0.
  Used for Apple system apps instead of Apple's logos.

## App Store icons (runtime, iPhone app)
- Fetched by the iPhone app from Apple's public App Store lookup (`artworkUrl100`), resized to 96 px and
  sent to the watch. They're the apps' own official icons, which are their owners' trademarks and copyright.
  Used only to identify the source app of a notification on the user's own devices. Review before any store release (R-13).
