# Fake Xtream / XMLTV server (tests only)

Local stand-in for an Xtream panel with US sports / news / locals channels and an XMLTV guide that is streamed slowly,
so the sign-in -> TV guide loading step can be seen and tested without a real provider. No accounts, no secrets.

    python3 server.py 8088 12     # guide takes ~12 s
    python3 server.py 8089 400    # very slow guide (timeout / Skip tests)

`LoginFlowUiTest` uses both ports and skips its guide tests when they are not running. An emulator reaches the box as
http://10.0.2.2:8088 (build a debug APK with `-PMRTV2_SERVER_URL=http://10.0.2.2:8088 -PMRTV_CRASH_WEBHOOK_URL= -PMRTV_CRASH_WEBHOOK_KEY=`).
