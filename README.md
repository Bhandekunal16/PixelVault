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
- **Local Persistence:** Jetpack DataStore Preferences for server settings and UI state
- **Testing:** JUnit 4 + Robolectric + Jetpack Compose UI Test Rule

---

## 2. API Endpoints Reference

The application interfaces directly with the following backend routes:

| HTTP Method | Endpoint | Description | Request Format |
|---|---|---|---|
| `GET` | `/` | Server availability & ping health check | None |
| `POST` | `/register` | User registration (Gmail only, 8+ char password) | `{"email": "person@gmail.com", "password": "..."}` |
| `POST` | `/login` | User authentication | `{"email": "person@gmail.com", "password": "..."}` |
| `POST` | `/image` | Upload & format conversion (max 10 MiB) | `multipart/form-data` (`image`, `email`, `format`) |
| `GET` | `/image?email={email}` | Retrieve image metadata list for user | Query param: `email=person%40gmail.com` |
| `GET` | `/image?id={id}` | Retrieve raw binary image data | Query param: `id=...` (Streams binary image) |
| `DELETE` | `/image` | Delete 1 to 100 images by ID | `{"ids": ["mongo-id-1", "mongo-id-2"]}` |

The backend's email-based metadata lookup omits image bytes, while image downloads return only the selected image and its response metadata. The backend also has an internal per-email storage-usage summary, but it is not exposed as an API endpoint and is not available to the app.

---

## 3. How to Start the Existing Node.js Backend

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

## 4. Configuring Backend Base URL

The application includes an in-app **Server Configuration** editor in the **Settings** tab with instant connection testing (`GET /` ping with latency readout):

### Default Backend Server
- **URL:** `http://147.224.251.137:8001/`
- **Default:** Pre-configured as the app's default backend endpoint.

### Standard Android Emulator
- **URL:** `http://10.0.2.2:8000/`
- **Explanation:** In the Android emulator, `localhost` points to the emulator itself. `10.0.2.2` is the special alias provided by the Android emulator routing table to access the host machine's `127.0.0.1`.

### Physical Android Device
- Ensure your physical phone and host computer are on the same Wi-Fi network.
- Determine your computer's local LAN IP:
  - macOS/Linux: `ifconfig` or `ip a` (e.g., `192.168.1.50`)
  - Windows: `ipconfig`
- Set the Base URL in the app's Settings to:
  `http://192.168.1.50:8000/`

### Staging or Production Server
- Use the secure HTTPS domain, e.g.:
  `https://api.yourdomain.com/`

---

## 5. Optional Response Encryption Compatibility

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

## 6. How to Open and Run in Android Studio

1. Open Android Studio (Ladybug / Iguana or later).
2. Select **File > Open** and choose this project root folder.
3. Wait for Gradle sync to complete.
4. Select an emulator or physical device.
5. Click the green **Run** (`Shift + F10`) button.

---

## 7. How to Build the APK

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

## 8. Backend Security Limitations & Production Hardening

Before deploying this system to production, the following security limitations of the current Node.js Express backend must be addressed:

1. **Absence of Token / Session Authentication:**
   - *Current Limitation:* The API does not issue JWT access tokens, refresh tokens, or HTTP-only session cookies upon `POST /login`. Currently, the Android app saves login state locally as a convenience.
   - *Production Fix:* Implement JWT (Bearer tokens) or secure session cookies in `POST /login` and validate them on every endpoint using an authentication middleware.

2. **Unauthenticated Image Deletion:**
   - *Current Limitation:* `DELETE /image` accepts arbitrary MongoDB Object IDs in the request body without checking if the requesting user owns those images.
   - *Production Fix:* Enforce server-side ownership authorization: `Image.deleteMany({ _id: { $in: ids }, ownerEmail: req.user.email })`.

3. **Email-based Query Exposure:**
   - *Current Limitation:* `GET /image?email=person@gmail.com` allows anyone who knows an email address to query their image metadata.
   - *Production Fix:* Derive user identity directly from the authenticated session/JWT rather than trusting unverified query parameters.

4. **HTTPS Transport Layer:**
   - Always serve the Express backend behind HTTPS with TLS 1.3 in production environments. Cleartext traffic (`usesCleartextTraffic="true"`) is enabled in `AndroidManifest.xml` strictly for local development against `http://10.0.2.2:8000/` and localhost.
