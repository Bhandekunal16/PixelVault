# ImageVault Android Application

A modern, native Android application built with Kotlin and Jetpack Compose (Material 3) that integrates with an existing Node.js Express REST API backend for image conversion, compression optimization, and secure cloud storage.

---

## 1. Architecture & Tech Stack

- **Platform:** Native Android (Min SDK 24, Target SDK 36)
- **Language:** Kotlin 2.2+ with Coroutines & Flow
- **UI Framework:** Jetpack Compose with Material Design 3 (Dynamic / Curated Theme)
- **Architecture:** MVVM (Model-View-ViewModel) + Clean Architecture Repository pattern
- **Networking:** Retrofit 2.12 + OkHttp 4.10 + Moshi (Kotlin JSON reflect & codegen)
- **Image Pipeline:** Coil 2.7 with crossfade, memory & disk caching
- **Media Picker:** Zero-permission Android Photo Picker (`ActivityResultContracts.PickVisualMedia`)
- **Local Persistence:** Room for account-scoped image metadata and sync state; DataStore Preferences for server settings and UI state
- **Testing:** JUnit 4 + Robolectric + Jetpack Compose UI Test Rule

---

## 2. API Endpoints Reference

The application interfaces directly with the following backend routes:

| HTTP Method | Endpoint | Description | Request Format |
|---|---|---|---|
| `GET` | `/` | Server availability & ping health check | None |
| `POST` | `/register` | User registration (Gmail only, 8+ char password) | `{"email": "person@gmail.com", "password": "..."}` |
| `POST` | `/login` | User authentication; returns an access token | `{"email": "person@gmail.com", "password": "..."}` |
| `POST` | `/image` | Authenticated upload & format conversion (max 10 MiB) | `multipart/form-data` (`image`, optional matching `email`, `format`) |
| `GET` | `/storage/usage` | Retrieve authenticated account cloud storage usage and quota | Bearer token |
| `GET` | `/image` | Retrieve metadata list for the authenticated user | Bearer token |
| `GET` | `/image?id={id}` | Retrieve an owned image's raw bytes | Bearer token; streams binary image |
| `DELETE` | `/image` | Delete 1 to 100 owned images by ID | Bearer token and `{"ids": ["mongo-id-1", "mongo-id-2"]}` |

Image API calls use the `Authorization: Bearer <accessToken>` header. Login returns `data.user`, `data.accessToken`, and `data.expiresAt` (Unix seconds). Metadata responses omit image bytes. The backend's internal storage-usage summary is not exposed as an API endpoint.

## 3. Local-First Image Behavior

> Phase 4 note: The earlier API overview sentence stating that storage usage is not exposed is outdated. The authenticated `GET /storage/usage` endpoint now provides server-calculated usage and quota information.

- My Images and the dashboard observe image metadata from the Room database, so previously cached records can be displayed before a network sync completes.
- A successful list sync replaces the cached records for that normalized email account in one transaction. Other account caches are kept separate. Automatic list sync is considered fresh for two minutes; an explicit refresh bypasses that window.
- Uploads and deletions remain online-only. Confirmed uploads are written to Room; successful or already-absent deletions remove the corresponding local records. The app does not queue mutations for later or automatically retry an upload with an uncertain network outcome.
- The backend currently has no image-update endpoint. Cloud storage usage and quota enforcement are server-side; the app caches the last confirmed usage summary in Room, scoped to the account, and labels cached information when it may be stale.
- Room image and storage-usage records are partitioned by normalized account email and scoped repository calls require the matching active session. The backend separately validates bearer tokens and scopes image access/deletion by stable user ID, with an email fallback only for legacy records without an owner ID.
- Metadata is persisted in Room; image bytes are fetched from the authenticated backend when needed. Coil provides its configured image memory/disk cache. Uploads and deletions are online-only; there is no durable mutation queue.

---

## 4. How to Start the Existing Node.js Backend

1. Navigate to your Node.js Express backend project directory:
   ```bash
   cd /path/to/express-backend
   ```
2. Install dependencies:
   ```bash
   npm install
   ```
3. Start the server on port 8000:
   ```bash
   npm start
   # or
   node index.js
   ```
4. Verify that the server is listening:
   ```bash
   curl http://localhost:8000/
   ```

---

## 5. Configuring Backend Base URL

The application includes an in-app **Server Configuration** editor in the **Settings** tab with instant connection testing (`GET /` ping with latency readout):

### Hosted API (default)
- **URL:** `http://147.224.251.137:8001/`
- **Warning:** This hosted endpoint currently supports HTTP only. Login passwords, image data, and bearer tokens are not encrypted in transit and may be intercepted. Use a trusted HTTPS endpoint for sensitive or production use.

### Standard Android Emulator
- **URL:** `http://10.0.2.2:8000/`
- **Explanation:** In the Android emulator, `localhost` points to the emulator itself. `10.0.2.2` is the special alias provided by the Android emulator routing table to access the host machine's `127.0.0.1`.

### Physical Android Device
- The hosted API URL above is available from physical devices with internet access.
- The Android app permits HTTP only for localhost, emulator loopback, and the exact hosted IP/port above. All other non-local API hosts require HTTPS.

### Staging or Production Server
- Use the secure HTTPS domain, e.g.:
  `https://api.yourdomain.com/`

---

## 6. Optional Response Encryption Compatibility

When the backend has `encryption_Interceptor` enabled, JSON responses are returned in the wrapper format:
```json
{"data": "<base64-ciphertext>"}
```

### Android App Compatibility
1. Navigate to the **Settings** tab.
2. Toggle **Response Decryption** to ON.
3. Enter the shared AES secret key.
4. The isolated `EncryptionInterceptor` (`com.example.data.api.EncryptionInterceptor`) will automatically intercept JSON responses, decrypt the ciphertext payload, and forward the decrypted JSON to Moshi.
5. **Binary Image Separation:** Binary image downloads (`GET /image?id={id}`) bypass JSON decryption completely to preserve raw image data.

---

## 7. How to Open and Run in Android Studio

1. Open Android Studio (Ladybug / Iguana or later).
2. Select **File > Open** and choose this project root folder.
3. Wait for Gradle sync to complete.
4. Select an emulator or physical device.
5. Click the green **Run** (`Shift + F10`) button.

---

## 8. How to Build the APK

To compile and build a release or debug APK from the command line:

```bash
# Build Debug APK
gradle assembleDebug

# Build Release APK
gradle assembleRelease
```

The APK will be generated at:
`app/build/outputs/apk/debug/app-debug.apk`

---

## 9. Authentication, storage, and deployment notes

- The backend requires `AUTH_TOKEN_SECRET` (at least 32 UTF-8 bytes) from its process environment or deployment secret manager. Set `AUTH_TOKEN_TTL_SECONDS` in the backend's `json/app.json` (default 12 hours; accepted range 300 seconds to 7 days). Do not commit the signing secret. See the backend [API reference](../api.md).
- The app encrypts the access token with AES-GCM using an Android Keystore key before persisting it in DataStore. Session-bearing DataStore files are excluded from Android backup.
- Tokens are stateless and cannot be revoked server-side before expiry. Clearing the app session removes the local token but does not invalidate a previously copied token.
- Deploy the backend behind a trusted HTTPS terminator and do not expose backend ports directly to the public internet. The Android client allows cleartext only for localhost, loopback, and the standard emulator host for development.
- Legacy image records without `ownerId` are served only when their stored email matches the authenticated account. Newly uploaded records use the stable user ID from the token.
- Account-scoped Room metadata remains on device after logout; logout clears credentials, not cached image metadata. Shared-device threat models may require explicit cache deletion on logout.
- Image update APIs, server-side logout/revocation, refresh tokens, and offline mutation queues are not implemented.
