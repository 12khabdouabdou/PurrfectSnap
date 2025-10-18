# Bypass Mechanism Implementation Details

## 1. Overview

This document details the implementation of the secure bypass mechanism in the PurrfectSnap application. The primary goal of this system is to download a closed-source bypass module (`bypass.dex`) from a remote server and load it into the application at runtime. The entire process is designed with multiple layers of security to ensure that the bypass module can only be accessed and used by legitimate, official builds of the application.

## 2. System Architecture

The system is composed of three main components that work together to ensure a secure workflow:

### a. Client-Side (PurrfectSnap App)

*   **`core/src/main/kotlin/me/rhunk/snapenhance/core/SecurityFeatures.kt`**: This is the central orchestrator on the client-side. It manages user consent, initiates the download process, handles the decryption and verification of the bypass module, and loads it into the application.
*   **Native Rust Library (`native/` module)**: This component is responsible for securely storing the secret API key. The key is embedded in the compiled native library at build time and is retrieved at runtime via a JNI call. This makes it extremely difficult for an attacker to extract the key from the application package.

### b. Server-Side (Cloudflare Worker)

A serverless Cloudflare Worker acts as a secure gatekeeper for the bypass module. Its responsibilities are:

1.  **Authentication**: It verifies that incoming requests contain the correct secret API key.
2.  **Secure File Streaming**: If authentication is successful, it fetches the encrypted bypass module from the latest GitHub release and streams it directly to the client. The direct URL to the bypass module is never exposed.

### c. Build Process (GitHub Actions)

The build process is a critical part of the security model. It is responsible for securely injecting the secret API key into the native library.

1.  **Secret Storage**: The secret API key is stored as an encrypted secret in the GitHub repository's settings.
2.  **Build-Time Injection**: During the GitHub Actions build, the secret key is passed as an environment variable to the build script. The Rust compiler then reads this environment variable and embeds the key directly into the compiled native library.

## 3. Detailed Workflow

Here is a step-by-step description of the entire process:

1.  **First Launch & User Consent**: When the app is launched for the first time, the `SecurityFeatures` class checks for a consent flag. If it's not found, it displays a dialog asking the user for their consent to download the closed-source bypass module.

2.  **The Secure Download Process**:
    *   **a. Pinned Connection**: The app establishes a secure HTTPS connection to the Cloudflare Worker endpoint. This connection is protected by **SSL Certificate Pinning**, which ensures the app is talking to the real server and not an imposter (preventing MITM attacks).
    *   **b. Client Authentication**: The app makes a request to the server, sending the secret API key in the `X-API-Key` header. The key is retrieved at runtime by calling the `getSecretKey()` function in the native Rust library.
    *   **c. Server-Side Verification**: The Cloudflare Worker receives the request, checks the `X-API-Key` header, and only proceeds if the key is correct.
    *   **d. Secure File Download**: If the key is correct, the server streams the encrypted `bypass.dex.enc` file from the latest GitHub release to the app.

3.  **On-Device Decryption and Loading**:
    *   **a. Checksum Verification**: Once the download is complete, the app calculates the SHA256 hash of the downloaded encrypted file and compares it to a known, hardcoded hash to ensure the file has not been tampered with.
    *   **b. Decryption**: If the checksum is valid, the app uses the same secret key (retrieved from the native library) to decrypt the file in memory using AES-256. The decryption process is compatible with the PBKDF2 key derivation standard used to encrypt the file.
    *   **c. Loading**: The decrypted `bypass.dex` file is written to a temporary file in the app's private storage, and then immediately loaded into the application using a `DexClassLoader`.

4.  **Cleanup**: Immediately after the module has been loaded, the decrypted `bypass.dex` file and the encrypted `bypass.dex.enc` file are deleted from the device's storage. This minimizes the time the files are present on the filesystem, making it extremely difficult for an attacker to extract them.

## 4. Security Layers Summary

This implementation uses a defense-in-depth approach, with multiple layers of security:

*   **Build-Time Secret Injection**: The secret key is not stored in the source code.
*   **Native Key Storage**: The key is embedded in a compiled native library, making it hard to extract.
*   **SSL Certificate Pinning**: Protects against Man-in-the-Middle (MITM) attacks.
*   **Server-Side Authentication**: The server verifies the client's identity before serving the file.
*   **End-to-End Encryption**: The bypass module is encrypted on the server and only decrypted in the app just before it is used.
*   **Checksum Verification**: Ensures the integrity of the downloaded file.
*   **Ephemeral File Storage**: The decrypted file is deleted immediately after use.

## 5. Maintenance

### a. Updating the Bypass Module

1.  Compile your updated `NewBypass.kt` file to get a new `bypass.dex`.
2.  Encrypt the new `bypass.dex` file using the `openssl` command:
    ```bash
    openssl enc -aes-256-cbc -pbkdf2 -iter 100000 -salt -in bypass.dex -out bypass.dex.enc -pass pass:<your_secret_key>
    ```
3.  Calculate the SHA256 hash of the new `bypass.dex.enc` file.
4.  Update the `BYPASS_SHA256` constant in `SecurityFeatures.kt` with the new hash.
5.  Create a new release on GitHub and upload the new `bypass.dex.enc` file as a release asset.

### b. Rotating the Secret Key

1.  Generate a new, secure secret key.
2.  Update the `BYPASS_SECRET_KEY` secret in your GitHub repository's settings.
3.  Update the `SECRET_KEY` constant in your Cloudflare Worker's `index.js` file and redeploy the worker.
4.  For local testing, update the `BYPASS_SECRET_KEY` environment variable on your local machine.
