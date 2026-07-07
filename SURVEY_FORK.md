# bitchat Forms — fork design & roadmap

Repurposes bitchat's Bluetooth mesh messenger into a distributed survey/forms tool:
design a survey on-device, publish it over the mesh, let any peer running this build
fill it out, and route responses back to the creator. Works offline over BLE mesh;
syncs over the internet (Nostr relays) when on wifi/data, reusing bitchat's existing
transport unchanged.

## Architecture mapping (onto existing bitchat code)

| Need                        | Reused bitchat mechanism                                             |
|-----------------------------|---------------------------------------------------------------------|
| Publish a survey to all     | `BitchatPacket` + `SpecialRecipients.BROADCAST`, multi-hop relay    |
| Large forms over BLE        | `FragmentManager` transport fragmentation (automatic)               |
| Reach offline/late peers    | `StoreForwardManager` store-and-forward                             |
| Responses back to creator   | `BitchatPacket` with creator `recipientID` (private routing)        |
| Internet sync (wifi/data)   | `nostr/` + `geohash/` Nostr relay path (already in the app)         |
| Structured payload encoding | JSON via `gson` (existing dep); pattern from `BitchatFilePacket`    |
| New message routing         | `MessageType` enum + `PacketProcessor` dispatch                     |

## Status

- [x] Protocol types added: `SURVEY_PUBLISH (0x30)`, `SURVEY_RESPONSE (0x31)`,
      `SURVEY_CLOSE (0x32)` in `protocol/BinaryProtocol.kt`.
- [x] Data model: `survey/Survey.kt` (`Survey`, `SurveyQuestion`, `SurveyResponse`,
      `SurveyClose`) with gson JSON encode/decode.
- [ ] `survey/SurveyManager.kt` — send/receive orchestration:
      `publishSurvey`, `submitResponse`, `closeSurvey`; dispatch from `PacketProcessor`.
- [ ] `PacketProcessor` cases for the three new types → SurveyManager.
- [ ] Persistence: `survey/SurveyStore.kt` — Room/DataStore for authored surveys,
      received surveys, and collected responses (dedupe by `responseId`).
- [ ] Nostr bridge: publish/response also over `NostrTransport` when online; dedupe
      mesh vs. internet copies.
- [ ] UI (Compose, in `ui/` or `survey/ui/`):
      - Survey list / home (replaces or sits beside chat entry)
      - Survey builder (add questions, choose type, options, required)
      - Survey fill-out screen
      - Results view (per-question aggregation + raw responses)
- [ ] Wire a mode/entry point so the app opens to Forms.

## Build → APK

No Android toolchain on the dev machine. Produce the `.apk` via the repo's existing
GitHub Actions workflow (`.github/workflows/android-build.yml`): fork, push the branch,
download the built APK artifact. Alternative: local Android Studio, or headless
JDK 17 + Android SDK + `./gradlew assembleRelease`.

## Notes / decisions

- This fork intentionally breaks iOS protocol compatibility for the new types (only
  this build understands them); base messaging types are untouched.
- Responses are routed to the creator's identity captured at publish time
  (`creatorID`, optional `creatorNostrPub`). If the creator is unreachable, mesh
  store-and-forward and Nostr relays hold responses until they reconnect.
- Anonymity: respondent identity is whatever bitchat identity is active; a future
  toggle can strip it for anonymous surveys.
