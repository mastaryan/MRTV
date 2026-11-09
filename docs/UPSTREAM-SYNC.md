# Pulling IPVIO updates into MR TV 2

MR TV 2 is a fork of [Emrodev/IPVIO-Stream](https://github.com/Emrodev/IPVIO-Stream). The fork changes live in a few
small places so new IPVIO versions can be merged on top:

| What | Where |
|---|---|
| App ID, name, version, update URL, metadata switch | `app/build.gradle.kts` |
| Runtime rename of "IPVIO" texts, credit constants | `app/src/main/java/com/ipvio/stream/Rebrand.kt` |
| Translations for fork texts | `.../DictMrtv.kt` |
| Credits in Settings > About | `.../MrtvCredits.kt` |
| Colors | `Look.kt` (first accent + background preset), `Ui.kt` (2 lines), `res/values*/mrtv_theme.xml` |
| Icons, banner, splash | `res/mipmap-*`, `res/drawable*/`, regenerate with `python3 branding/generate_icons.py` |
| TMDB/OMDb/TheIntroDB off | guarded by `Rebrand.METADATA_SERVICES` |

The Kotlin package stays `com.ipvio.stream` on purpose (only the applicationId changed), so upstream files keep
their paths.

## One-time setup

```bash
git remote add upstream https://github.com/Emrodev/IPVIO-Stream
git remote set-url --push upstream DISABLED   # never push to upstream by accident
```

## Each time IPVIO releases a new version

```bash
git checkout main
git pull origin main
git fetch upstream
git checkout -b sync-ipvio-X.Y          # work on a branch, open a PR into main
git merge upstream/main
```

Resolve conflicts:

- `app/build.gradle.kts`: keep **our** `applicationId`, `versionCode`/`versionName` (raise them!), `appLabel`,
  `UPDATE_URL` and `METADATA_SERVICES`; take **their** dependency updates.
- `res/mipmap-*`, `res/drawable*/banner.png`, `ic_logo_fg.png`, `ic_brand_mark.png`: keep **ours**
  (`git checkout --ours <file>`), or rerun `python3 branding/generate_icons.py`.
- `Look.kt` / `Ui.kt`: keep our first accent preset ("MR TV Silber") and first background ("MR TV Anthrazit").
- `SettingsHub.kt`: keep the `Rebrand.METADATA_SERVICES` guards and `hiddenSettingsPages()`; take their new settings.
- New IPVIO screens that call TMDB/OMDb/TheIntroDB directly: guard them with `Rebrand.METADATA_SERVICES`.
- New IPVIO texts containing "IPVIO" are renamed automatically by `Rebrand.apply`.

Then rebuild and test:

```bash
./gradlew testDebugUnitTest assembleRelease
grep -rn "IPVIO" app/src/main/java --include=*.kt | grep -v "Dict\|package\|import\|ipvio_"   # look for new hard-coded names
```

Bump `versionCode`/`versionName`, sign with the **same** MR TV 2 keystore, update `mrtv2-version.json` on
watchmrtv.com, and publish the matching source (GPL).
