# NOTICE – MR TV 2

**MR TV 2 is based on [IPVIO Stream](https://github.com/Emrodev/IPVIO-Stream) by Emrodev (GPL-3.0).**

MR TV 2 is a modified version of IPVIO Stream. It is distributed under the same license, the
GNU General Public License v3 (see `LICENSE`). Third-party components and their licenses are listed in
`THIRD_PARTY_NOTICES.md`; font licenses are in `licenses/`.

## Modifications (MR TV, watchmrtv.com, November 2026)

Changes made to IPVIO Stream 1.2 (upstream commit `b65e838`):

- New name **MR TV 2**, new app ID `com.watchmrtv.tv2`, version 1.0.0 (versionCode 1).
- MR TV logo as launcher icon, adaptive icon, Android TV / Fire TV banner, splash screen and in-app brand mark
  (`branding/`).
- Default design uses the watchmrtv.com colors (silver `#C9CED6` / `#E2E6EC` on charcoal `#0D0E10`).
- Third-party metadata services switched off: TMDB, OMDb (IMDb / Rotten Tomatoes ratings) and TheIntroDB.
  The app uses only the provider's own data (Xtream / M3U / Stalker). No API keys are needed.
  (`BuildConfig.METADATA_SERVICES`, see `app/build.gradle.kts`.)
- The in-app updater checks `https://watchmrtv.com/mrtv2-version.json` instead of IPVIO's GitHub releases.
- The upstream donation page is hidden; the upstream credit is shown in Settings > About.
- Visible "IPVIO" texts are shown as "MR TV 2" (`Rebrand.kt`, `DictMrtv.kt`).

All fork changes are kept in small, separate places (`Rebrand.kt`, `DictMrtv.kt`, `MrtvCredits.kt`,
`res/values*/mrtv_theme.xml`, `branding/`) so new IPVIO versions can be merged; see `docs/UPSTREAM-SYNC.md`.

## Source code

The complete source code of MR TV 2 is available at <https://github.com/mastaryan/MRTV>.
Upstream: <https://github.com/Emrodev/IPVIO-Stream>.

"MR TV" name and logo belong to MR TV (watchmrtv.com). "IPVIO" is the name of the upstream project;
MR TV 2 is not made or endorsed by the IPVIO developer.
