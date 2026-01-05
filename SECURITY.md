# Security Model

MetroVault is designed as a secure offline Bitcoin signing device. This document provides a detailed explanation of the security architecture, encryption model, and protective measures implemented throughout the application.

## Security Overview

MetroVault employs a **defense-in-depth** strategy with multiple layers of protection:

1. **Physical Layer**: Air-gapped operation (no network connectivity)
2. **Authentication Layer**: Password + optional biometric authentication
3. **Encryption Layer**: Dual-layer encryption (session key + Android Keystore)
4. **Application Layer**: Screenshot protection, clipboard clearing, autofill prevention
5. **Rate Limiting Layer**: Exponential backoff against brute force attacks

### Security Principles

- **Zero Network Access**: The app is designed to operate on a device with airplane mode enabled and all radios disabled
- **Keys Never Leave Device**: Private keys and mnemonics never touch any network interface
- **Memory Wiping**: Sensitive keys are wiped from RAM on logout
- **Minimal Attack Surface**: Focused functionality reduces potential vulnerabilities

---

## Authentication System

### Password-Based Authentication

When you set up MetroVault, you create a master password. This password:

1. **Is never stored** - only a secure hash is stored
2. **Derives the encryption key** - used to encrypt/decrypt wallet data
3. **Cannot be recovered** - there is no "forgot password" functionality by design

#### Password Hash Storage

```
Format: salt:hash:iterations (Base64 encoded)

Components:
- Salt: 256-bit random value (cryptographically secure)
- Hash: 256-bit PBKDF2 output
- Iterations: 210,000 (OWASP recommended minimum for 2024)
```

### Password Verification Flow

```
┌─────────────────────────────────────────────────────────────────┐
│                     Password Entry                              │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│              Rate Limit Check (LoginAttemptManager)             │
│   • Check if currently locked out                               │
│   • If locked: return error with remaining time                 │
└─────────────────────────────────────────────────────────────────┘
                              │ (not locked)
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                PBKDF2 Key Derivation (~200ms)                   │
│   • Algorithm: PBKDF2-HMAC-SHA256                               │
│   • Iterations: 210,000                                         │
│   • Output: 256-bit key                                         │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│           Constant-Time Hash Comparison                         │
│   • Uses MessageDigest.isEqual() to prevent timing attacks      │
└─────────────────────────────────────────────────────────────────┘
                              │
              ┌───────────────┴───────────────┐
              ▼                               ▼
        ┌───────────┐                   ┌───────────┐
        │  SUCCESS  │                   │  FAILURE  │
        └───────────┘                   └───────────┘
              │                               │
              ▼                               ▼
┌─────────────────────────────┐   ┌─────────────────────────────┐
│  Initialize Session Key     │   │  Record Failed Attempt      │
│  Reset attempt counter     │   │  Apply exponential backoff  │
└─────────────────────────────┘   └─────────────────────────────┘
```

---

## Encryption Architecture

MetroVault uses a **dual-layer encryption** model to protect sensitive wallet data:

### Layer 1: Session-Based Encryption (Application Level)

#### Key Derivation

```
┌─────────────────────────────────────────────────────────────────┐
│                    Master Key Derivation                        │
├─────────────────────────────────────────────────────────────────┤
│  Input:      User Password + Stored Salt                        │
│  Algorithm:  PBKDF2-HMAC-SHA256                                 │
│  Iterations: 210,000                                            │
│  Output:     256-bit Master Key                                 │
│  Timing:     ~200ms (intentionally slow)                        │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                    Wallet Key Derivation                        │
├─────────────────────────────────────────────────────────────────┤
│  Input:      Master Key + Context String ("wallet-encryption")  │
│  Algorithm:  HKDF-SHA256                                        │
│  Output:     256-bit Wallet Encryption Key                      │
│  Timing:     <1ms (fast, derived from already-slow master key)  │
└─────────────────────────────────────────────────────────────────┘
```

#### HKDF Implementation Details

The HKDF (HMAC-based Key Derivation Function) follows RFC 5869:

```
Extract Phase:
  PRK = HMAC-SHA256(salt=zeros[32], IKM=masterKey)

Expand Phase:
  OKM = HMAC-SHA256(PRK, info || 0x01)

Where:
  - PRK: Pseudo-Random Key (intermediate value)
  - IKM: Input Key Material (master key from PBKDF2)
  - OKM: Output Key Material (final wallet encryption key)
  - info: Context string (e.g., "wallet-encryption")
```

#### Data Encryption

All wallet secrets are encrypted using **AES-256-GCM**:

```
┌─────────────────────────────────────────────────────────────────┐
│                    AES-256-GCM Encryption                       │
├─────────────────────────────────────────────────────────────────┤
│  Algorithm:     AES-256-GCM (Galois/Counter Mode)               │
│  Key Size:      256 bits                                        │
│  IV Size:       96 bits (12 bytes, randomly generated)          │
│  Auth Tag:      128 bits                                        │
│                                                                 │
│  Encrypted Format: [IV (12 bytes)] + [Ciphertext] + [Auth Tag]  │
└─────────────────────────────────────────────────────────────────┘
```

**Security Properties of GCM:**
- **Confidentiality**: Data cannot be read without the key
- **Authenticity**: Any tampering is detected (via auth tag)
- **No Padding Oracle**: Block cipher modes like CBC are not used

### Layer 2: Android EncryptedSharedPreferences (Platform Level)

All app data is stored in `EncryptedSharedPreferences`, providing:

```
┌─────────────────────────────────────────────────────────────────┐
│              EncryptedSharedPreferences                         │
├─────────────────────────────────────────────────────────────────┤
│  Key Encryption:    AES-256-SIV (Synthetic IV mode)             │
│  Value Encryption:  AES-256-GCM                                 │
│  Key Storage:       Android Keystore (hardware-backed if avail) │
│                                                                 │
│  Benefits:                                                      │
│  • Keys stored in secure hardware (TEE/StrongBox)               │
│  • Keys never exposed to app layer                              │
│  • Resistant to device rooting (hardware-backed)                │
└─────────────────────────────────────────────────────────────────┘
```

### Dual-Layer Security Diagram

```
┌─────────────────────────────────────────────────────────────────┐
│                          Wallet Secrets                         │
│                  (mnemonic, BIP39 seed)                         │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│              Layer 1: Session Key Encryption                    │
│                                                                 │
│  plaintext ──► AES-256-GCM (Session Key) ──► ciphertext         │
│                                                                 │
│  Protection: Password-derived, wiped on logout                  │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│         Layer 2: EncryptedSharedPreferences                     │
│                                                                 │
│  ciphertext ──► AES-256-GCM (Keystore Key) ──► stored blob      │
│                                                                 │
│  Protection: Hardware-backed, survives app restart              │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                      Device Storage                             │
│                                                                 │
│  Even if extracted, data requires:                              │
│  1. Keystore key (hardware-protected)                           │
│  2. User password (to derive session key)                       │
└─────────────────────────────────────────────────────────────────┘
```

---

## Data Storage Security

### Data Separation Model

Wallet data is split into two categories with different security levels:

#### Metadata (Non-Sensitive)
```
┌─────────────────────────────────────────────────────────────────┐
│                     Wallet Metadata                             │
├─────────────────────────────────────────────────────────────────┤
│  Contents:                                                      │
│  • Wallet ID (UUID)                                             │
│  • Wallet Name                                                  │
│  • Derivation Path (e.g., m/84'/0'/0')                          │
│  • Master Fingerprint                                           │
│  • Has Passphrase flag                                          │
│  • Creation Timestamp                                           │
│                                                                 │
│  Storage: Plain JSON within EncryptedSharedPreferences          │
│  Encryption: Single layer (platform only)                       │
└─────────────────────────────────────────────────────────────────┘
```

#### Secrets (Highly Sensitive) - WalletKeys
```
┌─────────────────────────────────────────────────────────────────┐
│                       Wallet Keys                               │
├─────────────────────────────────────────────────────────────────┤
│  Contents:                                                      │
│  • Key ID (UUID - unique identifier)                            │
│  • Mnemonic Phrase (12 or 24 words)                             │
│  • BIP39 Seed (512-bit, hex-encoded)                            │
│  • Master Fingerprint (8 hex chars)                             │
│  • User Label (e.g., "Key 1", "Cold Storage")                   │
│                                                                 │
│  ARCHITECTURE:                                                  │
│  • Keys are stored SEPARATELY from wallets                      │
│  • Multiple wallets can reference the same key (via keyId)      │
│  • Single-sig wallets have exactly 1 keyId reference            │
│  • Multisig wallets have 0-N keyId references (local signers)   │
│  • Keys only deleted when no wallet references them             │
│                                                                 │
│  IMPORTANT: The raw passphrase is NEVER stored to disk.         │
│  Only the derived BIP39 seed is saved, which is computed        │
│  using PBKDF2 (mnemonic + passphrase). This seed cannot be      │
│  reversed to recover the original passphrase.                   │
│                                                                 │
│  Storage: Session-key encrypted within EncryptedSharedPreferences│
│  Encryption: Dual layer (session key + platform)                │
└─────────────────────────────────────────────────────────────────┘
```

#### Passphrase-Protected Wallets (Session-Only Passphrase Mode)

Users can choose **not to save their passphrase locally**. In this case:

```
┌─────────────────────────────────────────────────────────────────┐
│               Session-Only Passphrase Mode                      │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Storage Behavior:                                              │
│  • Mnemonic: Always saved (encrypted)                           │
│  • BIP39 Seed: Base seed (without passphrase) saved to disk     │
│  • Passphrase: NEVER stored to disk in any form                 │
│                                                                 │
│  On Wallet Open:                                                │
│  • User prompted to enter passphrase                            │
│  • Correct BIP39 seed computed in RAM (mnemonic + passphrase)   │
│  • Session seed stored in memory, wiped on logout               │
│                                                                 │
│  Security Benefits:                                             │
│  • Passphrase never touches persistent storage                  │
│  • Even if device seized, passphrase cannot be extracted        │
│  • Protects against malware extracting encrypted storage        │
│  • Higher security for high-value wallets                       │
│  • Passphrase reuse on other services cannot be compromised     │
│                                                                 │
│  Trade-offs:                                                    │
│  • Less convenient (re-entry required each session)             │
│  • User MUST remember passphrase (no recovery possible)         │
│                                                                 │
│  Fingerprint Mismatch Detection:                                │
│  • Master fingerprint displayed in RED if wrong passphrase      │
│  • Stored fingerprint is from the WITH-passphrase derivation    │
│  • Allows user to verify correct passphrase was entered         │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

#### Session Seed Memory Security

When session seeds are stored in RAM, they use a specialized `SecureSeedCache` instead of plain String objects:

```
┌─────────────────────────────────────────────────────────────────┐
│               Session Seed Memory Protection                    │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Problem with Java Strings:                                     │
│  • Strings are IMMUTABLE in the JVM                             │
│  • String contents cannot be overwritten/zeroed                 │
│  • Calling clear() on a map only removes the reference          │
│  • Actual string data remains in heap until garbage collected   │
│  • GC timing is unpredictable - seeds may linger in memory      │
│                                                                 │
│  Solution - SecureSeedCache:                                    │
│  • Seeds stored as byte arrays (SecureByteArray wrapper)        │
│  • Byte arrays CAN be zeroed (filled with 0x00)                 │
│  • On clear(): all bytes zeroed BEFORE removing references      │
│  • On replace: old seed wiped before storing new one            │
│  • Deterministic cleanup - no waiting for GC                    │
│                                                                 │
│  Memory Wipe Flow:                                              │
│                                                                 │
│  sessionSeeds.clear()                                           │
│       │                                                         │
│       ▼                                                         │
│  For each seed:                                                 │
│    1. Get underlying byte array                                 │
│    2. Fill with zeros: Arrays.fill(data, 0)                     │
│    3. Set reference to null                                     │
│       │                                                         │
│       ▼                                                         │
│  Clear map references                                           │
│       │                                                         │
│       ▼                                                         │
│  Seed data is GONE (not waiting for GC)                         │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

#### BIP39 Seed Storage Security

```
┌─────────────────────────────────────────────────────────────────┐
│               BIP39 Seed Storage Model                          │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Wallet Creation:                                               │
│                                                                 │
│  1. User provides: mnemonic + passphrase                        │
│  2. BIP39 seed derived: PBKDF2(mnemonic, passphrase, 2048 iter) │
│  3. Stored to disk:                                             │
│     • mnemonic (encrypted)                                      │
│     • bip39Seed (512-bit hex, encrypted) ← NOT the passphrase   │
│                                                                 │
│  Why This is Secure:                                            │
│  • PBKDF2 is one-way function (cannot reverse to passphrase)    │
│  • Seed is 512 bits of entropy (infeasible to brute force)      │
│  • Passphrase commonly reused - seed is unique to this wallet   │
│                                                                 │
│  Performance Benefit:                                           │
│  • Wallet loading skips PBKDF2 (seed already derived)           │
│  • Faster app responsiveness on wallet open                     │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### Session Key Lifecycle

```
┌─────────────────────────────────────────────────────────────────┐
│                    Key Lifecycle                                │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  App Start ────► Keys not in memory                             │
│                                                                 │
│  Login ────────► PBKDF2 derives master key (~200ms)             │
│              └─► HKDF derives wallet key (<1ms)                 │
│              └─► Keys stored in RAM only                        │
│                                                                 │
│  Operations ───► Use cached keys (instant)                      │
│                                                                 │
│  Logout ───────► Keys wiped from RAM                            │
│              └─► Arrays overwritten with zeros                  │
│              └─► References set to null                         │
│                                                                 │
│  App Kill ─────► RAM cleared by OS                              │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

---

## Biometric Authentication

MetroVault supports fingerprint/face unlock as a convenient alternative to password entry, while maintaining strong security guarantees.

### Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                Biometric Authentication Flow                    │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│              Hardware-Backed Key Generation                     │
│                                                                 │
│  • Key stored in Android Keystore (TEE/StrongBox)               │
│  • setUserAuthenticationRequired(true)                          │
│  • Key can ONLY be used after valid biometric                   │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│           Encrypted Password Storage                            │
│                                                                 │
│  On Setup:                                                      │
│  1. Generate biometric-gated AES key in Keystore                │
│  2. Encrypt password with this key                              │
│  3. Store encrypted password + IV                               │
│                                                                 │
│  On Login:                                                      │
│  1. Initialize cipher with Keystore key                         │
│  2. Authenticate with biometric (unlocks key)                   │
│  3. Decrypt password                                            │
│  4. Use password to derive session key                          │
└─────────────────────────────────────────────────────────────────┘
```

### Security Properties

| Property | Implementation |
|----------|---------------|
| **Algorithm** | AES-256-GCM |
| **Key Storage** | Android Keystore (hardware-backed when available) |
| **Authentication** | BIOMETRIC_STRONG (Class 3 biometrics only) |
| **Key Access** | Requires valid biometric on each use |
| **Invalidation** | Key invalidated if biometrics change (new fingerprint enrolled) |

### Crypto Object Binding

The biometric prompt uses a **CryptoObject** binding:

```kotlin
// Key can ONLY be used after successful biometric authentication
biometricPrompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(cipher))
```

This ensures:
- The decryption cipher is unusable until biometric succeeds
- Malware cannot extract the password without user's biometric
- The key remains hardware-protected at all times

---

## Brute Force Protection

### Rate Limiting Strategy

MetroVault implements **exponential backoff** to prevent brute force attacks:

```
┌────────────────────────────────────────────────────────────────┐
│                 Failed Attempt Delays                          │
├────────────────────────────────────────────────────────────────┤
│  Attempt 1:    No delay                                        │
│  Attempt 2:    30 seconds                                      │
│  Attempt 3:    1 minute                                        │
│  Attempt 4:    5 minutes                                       │
│  Attempt 5:    15 minutes                                      │
│  Attempt 6+:   1 hour each                                     │
│  Attempt 20:   24-hour lockout                                 │
└────────────────────────────────────────────────────────────────┘
```

### Attack Mitigation Analysis

```
┌─────────────────────────────────────────────────────────────────┐
│                 Brute Force Comparison                          │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  WITHOUT Rate Limiting:                                         │
│  • ~1,000 attempts/second possible                              │
│  • 4-digit PIN: cracked in ~10 seconds                          │
│  • 6-character password: hours to days                          │
│                                                                 │
│  WITH Rate Limiting:                                            │
│  • Maximum ~6 attempts per day                                  │
│  • 4-digit PIN: ~4.5 years to exhaust                           │
│  • 6-character password: effectively impossible                 │
│                                                                 │
│  Combined with PBKDF2 (210,000 iterations):                     │
│  • Each attempt takes ~200ms of CPU time                        │
│  • GPU acceleration limited (PBKDF2 is memory-hard)             │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### Persistence

Rate limit state is **persisted to disk**:
- Survives app restarts and device reboots
- Prevents bypassing via app force-stop
- Stored in separate SharedPreferences file

---

## Plausible Deniability

MetroVault supports a **decoy password** feature for plausible deniability under duress.

### How It Works

```
┌─────────────────────────────────────────────────────────────────┐
│                   Dual Password System                          │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  MAIN PASSWORD                    DECOY PASSWORD                │
│  ═══════════════                  ═══════════════               │
│  • Real wallets                   • Decoy wallets               │
│  • Real funds                     • Small/fake amounts          │
│  • Full access                    • Appears identical           │
│                                                                 │
│  Same app, same UI - only the password determines which         │
│  wallet set is loaded                                           │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### Technical Implementation

- **Separate Storage**: Main and decoy data stored in different EncryptedSharedPreferences files
- **Independent Keys**: Each password derives its own session key
- **No Cross-References**: Decoy mode has no access to main wallet data
- **Identical UX**: App behavior is indistinguishable between modes

### Coercion Scenario

If forced to unlock the device:
1. Enter decoy password
2. App shows decoy wallets with minimal funds
3. Attacker sees a functional wallet app
4. Real funds remain hidden and inaccessible

---

## UI Security Measures

### Screenshot Protection

```kotlin
// Applied to all activities
window.setFlags(
    WindowManager.LayoutParams.FLAG_SECURE,
    WindowManager.LayoutParams.FLAG_SECURE
)
```

This prevents:
- Screenshots
- Screen recording
- Display on non-secure external displays
- App preview in recent apps (shows blank)

### Autofill Prevention

All sensitive input fields use a secure text field that:

```kotlin
// Prevents keyboard/system autofill suggestions
view.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS

// Disables autocorrect (prevents learning from sensitive input)
keyboardOptions = keyboardOptions.copy(autoCorrectEnabled = false)
```

This prevents:
- Password managers from capturing mnemonic phrases
- Keyboard learning from sensitive input
- Autofill popups revealing sensitive fields exist

### Clipboard Security

When copying sensitive data (addresses, XPUBs):

```kotlin
// Auto-clear after 20 seconds
copyToClipboardWithAutoClear(context, label, sensitiveText, delayMs = 20_000)
```

Behavior:
- Data copied to clipboard
- Background timer starts
- After delay, clipboard is cleared (only if unchanged)
- Prevents stale sensitive data in clipboard

---

## Air-Gap Security

### Recommended Device Configuration

```
┌─────────────────────────────────────────────────────────────────┐
│              Air-Gapped Device Setup                            │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  ✓ Airplane mode: ENABLED                                       │
│  ✓ WiFi: DISABLED                                               │
│  ✓ Bluetooth: DISABLED                                          │
│  ✓ NFC: DISABLED                                                │
│  ✓ Mobile data: DISABLED / No SIM card                          │
│  ✓ Location services: DISABLED                                  │
│  ✓ USB debugging: DISABLED                                      │
│                                                                 │
│  Recommended: Factory reset before installing MetroVault        │
│  Recommended: Remove Google account / use offline setup         │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### QR Code Air-Gap Transaction Flow

```
┌───────────────────┐                    ┌───────────────────┐
│   ONLINE DEVICE   │                    │  OFFLINE DEVICE   │
│   (Watch-Only)    │                    │   (MetroVault)    │
└─────────┬─────────┘                    └─────────┬─────────┘
          │                                        │
          │   1. Create unsigned PSBT              │
          │   2. Display as QR code                │
          ├───────────────────────────────────────►│
          │        (Camera scan - no network)      │
          │                                        │
          │                              3. Verify transaction
          │                              4. Sign with private key
          │                              5. Display signed PSBT
          │        (Camera scan - no network)      │
          │◄───────────────────────────────────────┤
          │                                        │
          │   6. Broadcast to network              │
          │                                        │
```

---

## Cryptographic Standards

### Algorithms Used

| Purpose | Algorithm | Standard |
|---------|-----------|----------|
| Password Hashing | PBKDF2-HMAC-SHA256 | NIST SP 800-132 |
| Key Derivation | HKDF-SHA256 | RFC 5869 |
| Data Encryption | AES-256-GCM | NIST SP 800-38D |
| Biometric Key | AES-256-GCM (Keystore) | Android Keystore API |
| Key Encryption (ESP) | AES-256-SIV | RFC 5297 |
| Random Generation | SecureRandom | FIPS 140-2 |

### OWASP Compliance

| Requirement | MetroVault Implementation | Status |
|-------------|--------------------------|--------|
| PBKDF2 iterations ≥ 210,000 | 210,000 iterations | ✓ |
| Salt ≥ 128 bits | 256-bit salt | ✓ |
| Use authenticated encryption | AES-GCM with 128-bit tag | ✓ |
| Rate limit login attempts | Exponential backoff + permanent lockout | ✓ |
| Secure key storage | Android Keystore (hardware-backed) | ✓ |

---

## Security Reporting

If you discover a security vulnerability in MetroVault, please report it responsibly:

1. **Do not** open a public GitHub issue
2. Email the maintainer directly (check repository for contact)
3. Provide detailed reproduction steps
4. Allow reasonable time for a fix before disclosure

---

## Summary

MetroVault implements multiple layers of security to protect your Bitcoin:

| Layer | Protection |
|-------|------------|
| **Air-Gap** | No network = no remote attack surface |
| **Password** | PBKDF2 with 210k iterations prevents offline brute force |
| **Encryption** | Dual-layer AES-256-GCM protects data at rest |
| **Biometrics** | Hardware-backed keys with crypto binding |
| **Rate Limiting** | Exponential backoff prevents online brute force |
| **Plausible Deniability** | Decoy password protects against coercion |
| **UI Hardening** | Screenshot/autofill/clipboard protection |

The combination of these measures means that even if an attacker has physical access to your device, they cannot access your Bitcoin without your password (and defeating rate limiting, PBKDF2 costs, and hardware-backed encryption).
