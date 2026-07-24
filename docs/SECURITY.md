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

1. **Is never stored** - only a one-way verifier is stored
2. **Derives the encryption key** - used to encrypt/decrypt wallet data
3. **Cannot be recovered** - there is no "forgot password" functionality by design

#### Password Record Storage

```
Format: salt:verifier:iterations:version (Base64 encoded)

Components:
- Salt:       256-bit random value (cryptographically secure)
- Verifier:   256-bit HKDF-SHA256(masterKey, "password-verification")
- Iterations: PBKDF2 count used for THIS record (600,000 for new records)
- Version:    record format version (2 = verifier format)
```

**Domain separation (important):** The stored verifier is derived from the
PBKDF2 master key through a one-way HKDF step with a dedicated context string.
The wallet encryption key is derived from the same master key with a
*different* context string ("wallet-encryption") and exists only in RAM. An
attacker who reads the stored verifier therefore cannot compute the wallet
encryption key — password verification and data encryption are cryptographically
separated.

**Legacy records:** Versions prior to the verifier format stored the raw PBKDF2
output (which doubled as the master key) in a 3-part `salt:hash:iterations`
record. Such records are still accepted and are transparently upgraded to the
verifier format on the first successful password check. The master key is
unchanged by the upgrade, so no wallet data needs re-encryption. Legacy records
keep their original iteration count (210,000) until the next password change,
which re-derives everything at the current count.

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
│              PBKDF2 Key Derivation (intentionally slow)         │
│   • Algorithm: PBKDF2-HMAC-SHA256                               │
│   • Iterations: from the stored record (600,000 for new)        │
│   • Output: 256-bit master key                                  │
│   • Runs ONCE per candidate vault (main, then decoy)            │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│           Verifier Derivation + Constant-Time Comparison        │
│   • Candidate verifier = HKDF(masterKey, "password-verification")│
│   • Compared with stored verifier via MessageDigest.isEqual()   │
│     to prevent timing attacks                                   │
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
│  Session initialized from   │   │  Record Failed Attempt      │
│  the already-derived master │   │  Apply exponential backoff  │
│  key (no second PBKDF2 run) │   │  (skipped for biometric-    │
│  Reset attempt counter      │   │   originated attempts)      │
│  Upgrade legacy record      │   └─────────────────────────────┘
└─────────────────────────────┘
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
│  Iterations: from stored record (600,000 for new records)       │
│  Output:     256-bit Master Key (RAM only, never stored)        │
│  Timing:     intentionally slow (hundreds of ms)                │
└─────────────────────────────────────────────────────────────────┘
                              │
              ┌───────────────┴───────────────┐
              ▼                               ▼
┌─────────────────────────────┐ ┌─────────────────────────────────┐
│  Password Verifier          │ │  Wallet Encryption Key          │
├─────────────────────────────┤ ├─────────────────────────────────┤
│  HKDF-SHA256 with context   │ │  HKDF-SHA256 with context       │
│  "password-verification"    │ │  "wallet-encryption"            │
│  → 256-bit verifier         │ │  → 256-bit encryption key       │
│  STORED ON DISK             │ │  RAM ONLY, wiped on logout      │
│  (one-way: cannot yield the │ │  Timing: <1ms                   │
│   encryption key)           │ │                                 │
└─────────────────────────────┘ └─────────────────────────────────┘
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
  - OKM: Output Key Material (derived key)
  - info: Context string — "wallet-encryption" for the data encryption
    key (RAM only), "password-verification" for the stored verifier
```

The two context strings give domain-separated outputs: the stored verifier
and the encryption key are independent HKDF outputs of the same master key,
so possession of one reveals nothing about the other.

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
│  Login ────────► PBKDF2 derives master key (intentionally slow) │
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

All session key state is guarded by a lock: a concurrent emergency wipe
(triggered by lifecycle events) can never hand a partially-zeroed key to an
in-flight encryption — an operation either gets a complete copy of the key or
fails cleanly. The same locking applies to the in-memory seed cache
(`SecureByteArray`).

### Password Change

Changing a vault's password re-encrypts that vault's key material atomically:

1. The **old** vault key is re-derived from the old password + that vault's
   stored salt — deliberately *not* taken from the active session, so changing
   the decoy password from a main-vault session (or vice versa) always uses
   the correct key
2. Every encrypted blob in the vault is decrypted with the old key and
   re-encrypted with the new key in memory
3. The new password record (current iteration count, verifier format) and all
   re-encrypted blobs are written in a single atomic commit — a crash before
   the commit leaves the vault fully intact under the old password
4. The active session is switched to the new key only if it belongs to the
   vault being changed
5. A new password is rejected if it matches the other vault's password (with a
   deliberately neutral error message that does not reveal the other vault's
   existence)

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
| **Key Access** | Requires valid biometric on each use; on API 30+ the key is explicitly pinned to auth-per-use with `AUTH_BIOMETRIC_STRONG` (device credential can never unlock it) |
| **Invalidation** | Key invalidated if biometrics change (new fingerprint enrolled) |

### Stale Credential Handling

The biometric-stored password can go stale if the vault password is changed:

- After a password change, the app immediately prompts to re-encrypt the new
  password under the biometric key. **Any outcome other than a successful
  update — cancellation, error, or store failure — disables biometric unlock**
  and removes the stale ciphertext, rather than leaving an old password armed
- If a biometric unlock ever decrypts a password that no longer matches any
  vault, biometric unlock is automatically disabled with an explanatory message
- Failed logins originating from biometric-decrypted passwords are **not**
  counted toward the rate limiter or the optional data-wipe counter — they are
  machine-originated, not evidence of brute force

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
│  Attempts 1-2:  No delay (2 free retries for typos)            │
│  Attempt 3:     30 seconds                                     │
│  Attempt 4:     1 minute                                       │
│  Attempt 5:     5 minutes                                      │
│  Attempt 6:     15 minutes                                     │
│  Attempt 7+:    1 hour each                                    │
│  Attempt 20+:   24-hour lockout per failure                    │
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
│  Combined with PBKDF2 (600,000 iterations for new records):     │
│  • Each attempt costs hundreds of ms of CPU time on-device,     │
│    and the same cost applies to offline cracking of the         │
│    stored verifier                                              │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### Persistence and Clock Manipulation

Rate limit state is **persisted to disk**:
- Survives app restarts and device reboots
- Prevents bypassing via app force-stop
- Stored in separate SharedPreferences file

Because the device is air-gapped, there is no trusted network time and the
wall clock can be freely changed in system settings. To blunt the obvious
bypass (advancing the clock to skip a lockout), every lockout deadline is
recorded against **both** clocks:

- Wall clock (`currentTimeMillis`) — survives reboots
- Monotonic clock (`elapsedRealtime`) — cannot be adjusted by the user

The **stricter of the two** is enforced. Advancing the wall clock therefore
does not shorten a lockout; the only way to reset the monotonic deadline is a
full reboot, after which the wall-clock deadline still applies. This raises
the cost of clock-manipulation attacks without requiring secure time.

### Optional Data Wipe on Failed Logins

Users can enable **"Wipe Data on Failed Login"** in Security settings. When
enabled, the 4th consecutive failed password attempt permanently destroys all
app data: both vaults, password records, biometric ciphertexts and their
Keystore keys, settings, and session state. Failed attempts originating from
biometric unlock never count toward this threshold. This is an explicit
opt-in, protected by a confirmation dialog, intended for high-threat models
where seizure of the device is the primary concern.

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
- **Collision Prevention**: The two passwords can never be set (or changed) to
  the same value; the rejection message is deliberately neutral so a decoy-mode
  user never learns that another vault exists
- **Isolated Password Changes**: Changing either vault's password derives keys
  from that vault's own stored record and only re-encrypts that vault — the
  operation is independent of which vault the current session belongs to

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
| PBKDF2-HMAC-SHA256 iterations ≥ 600,000 | 600,000 for new records (legacy records upgraded at next password change) | ✓ |
| Salt ≥ 128 bits | 256-bit salt | ✓ |
| Don't store password-equivalent material | One-way HKDF verifier stored; master key never persisted | ✓ |
| Use authenticated encryption | AES-GCM with 128-bit tag | ✓ |
| Rate limit login attempts | Exponential backoff + 24h lockout, dual-clock enforcement | ✓ |
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
| **Password** | PBKDF2 (600k iterations for new records) prevents offline brute force |
| **Verifier Storage** | Only a one-way verifier is stored — it cannot yield the encryption key |
| **Encryption** | Dual-layer AES-256-GCM protects data at rest |
| **Biometrics** | Hardware-backed keys with crypto binding; stale credentials auto-disabled |
| **Rate Limiting** | Exponential backoff on both wall and monotonic clocks |
| **Optional Wipe** | Opt-in destruction of all data after 4 consecutive failed logins |
| **Plausible Deniability** | Decoy password protects against coercion |
| **UI Hardening** | Screenshot/autofill/clipboard protection |

The combination of these measures means that even if an attacker has physical access to your device, they cannot access your Bitcoin without your password (and defeating rate limiting, PBKDF2 costs, and hardware-backed encryption).
