# Interface languages

Sonora 0.8 includes English, Spanish and Catalan. The initial selection follows the system language, falling back to English for unsupported languages.

Use **About Sonora → App language** to choose **System language**, **English**, **Español** or **Català**. On Android 13 and newer, this uses Android's per-app language setting and stays in sync with the language selection in Android Settings. On Android 8–12, the choice is saved locally and applied when the activity is recreated. Changing the interface language does not erase the library, download audio again or stop the playback service.

App-owned screen text, player controls, accessibility descriptions, download notifications and error templates use Android resources in `values`, `values-es` and `values-ca`. The Kotlin `tr` calls are explicit: track/artist names, playlist names, user searches, provider descriptions and other external content are never automatically translated. Existing saved metadata and past operation messages retain the text they were created with; future operations use the current language. Release notes and upstream licence texts retain their original language.

To edit translations, change the matching string resource in all three files. If an English template changes or is added, run:

```sh
python3 tools/verify-translations.py --write
```

CI runs the verifier without `--write` to catch missing keys, mismatched formatting arguments, unregistered template calls and a stale generated lookup table. `LanguageTest` checks resource rendering, formatting, legacy persistence and live enum labels; release device checks exercise the real language picker and activity recreation.

Platform reference: [Android per-app language preferences](https://developer.android.com/guide/topics/resources/app-languages).
