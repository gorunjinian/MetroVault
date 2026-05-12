# Seed Generation, Storage & Usage

This document explains how MetroVault generates, stores, and uses Bitcoin wallet seeds following BIP-39 and BIP-32 standards.

## Overview

MetroVault follows the standard Bitcoin wallet derivation process:

```
┌───────────────────────────────────────────────────────────────────────────────┐
│                          Seed Generation Flow                                 │
└───────────────────────────────────────────────────────────────────────────────┘

     Entropy                 Mnemonic                 Seed                   Keys
  (128/256 bits)    ──►    (12/24 words)    ──►    (512 bits)    ──►    (BIP-32 tree)
                                  │
                          Optional Passphrase
```

| Stage | Standard | Algorithm |
|-------|----------|-----------|
| Entropy → Mnemonic | BIP-39 | SHA-256 checksum |
| Mnemonic → Seed | BIP-39 | PBKDF2-HMAC-SHA512 (2048 iterations) |
| Seed → Master Key | BIP-32 | HMAC-SHA512 |
| Key Derivation | BIP-32 | HMAC-SHA512 |

---

## Entropy Sources

MetroVault provides three methods for generating entropy:

### 1. System Entropy (Default)

Uses Android's `SecureRandom`, which sources entropy from:

```
┌─────────────────────────────────────────────────────────────────┐
│                     SecureRandom Sources                        │
├─────────────────────────────────────────────────────────────────┤
│  • /dev/urandom (kernel entropy pool)                           │
│  • Hardware RNG (if device has dedicated chip)                  │
│  • Environmental noise (timing, sensor data)                    │
│  • StrongBox/TEE entropy (on supported devices)                 │
└─────────────────────────────────────────────────────────────────┘
```

### 2. Coin Flip Entropy

For users who prefer verifiable randomness:

```
┌─────────────────────────────────────────────────────────────────┐
│                      Coin Flip Method                           │
├─────────────────────────────────────────────────────────────────┤
│  • Heads = 0, Tails = 1                                         │
│  • Each flip = 1 bit of entropy                                 │
│  • 12-word mnemonic: requires 128 flips                         │
│  • 24-word mnemonic: requires 256 flips                         │
│                                                                 │
│  Packing: 8 flips → 1 byte                                      │
│  Example: H,T,T,H,T,H,H,T → 0b01101001 → 0x69                   │
└─────────────────────────────────────────────────────────────────┘
```

### 3. Dice Roll Entropy

Casino-grade dice provide excellent physical randomness:

```
┌─────────────────────────────────────────────────────────────────┐
│                      Dice Roll Method                           │
├─────────────────────────────────────────────────────────────────┤
│  • Each roll (1-6) contributes ~2.58 bits (log₂(6))             │
│  • Two rolls combined: (roll1 - 1) × 6 + (roll2 - 1)            │
│  • Result: 0-35 packed into one byte                            │
│                                                                 │
│  12-word mnemonic: ~50 rolls (128 bits / 2.58 bits per roll)    │
│  24-word mnemonic: ~100 rolls                                   │
└─────────────────────────────────────────────────────────────────┘
```

### Entropy Mixing (When User Entropy Is Provided)

User entropy is **never used alone**. It is always mixed with system entropy:

```
┌─────────────────────────────────────────────────────────────────┐
│                     Entropy Mixing Process                      │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  userEntropy (coin/dice)  +  systemEntropy (SecureRandom)       │
│                    │                  │                         │
│                    └────────┬─────────┘                         │
│                             │                                   │
│                             ▼                                   │
│                     SHA-256(combined)                           │
│                             │                                   │
│                             ▼                                   │
│                  Take first N bytes (16 or 32)                  │
│                             │                                   │
│                             ▼                                   │
│                       Final Entropy                             │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

**Security Guarantee**: Even if the user's coin flips are biased or dice are loaded, the result is cryptographically secure because system entropy is always included.

---

## BIP-39 Mnemonic Generation

### Entropy to Mnemonic Conversion

The BIP-39 mnemonic encoding process:

```
┌─────────────────────────────────────────────────────────────────┐
│                  BIP-39 Encoding Process                        │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  1. Generate entropy (128 or 256 bits)                          │
│                                                                 │
│  2. Compute checksum: SHA-256(entropy)                          │
│                                                                 │
│  3. Append checksum bits to entropy:                            │
│     • 128-bit entropy + 4 checksum bits = 132 bits → 12 words   │
│     • 256-bit entropy + 8 checksum bits = 264 bits → 24 words   │
│                                                                 │
│  4. Split into 11-bit groups                                    │
│                                                                 │
│  5. Map each 11-bit value (0-2047) to wordlist index            │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### Mnemonic Word Count Options

| Words | Entropy Bits | Checksum Bits | Security Level |
|-------|-------------|---------------|----------------|
| 12 | 128 | 4 | 2¹²⁸ ≈ 10³⁸ combinations |
| 15 | 160 | 5 | 2¹⁶⁰ ≈ 10⁴⁸ combinations |
| 18 | 192 | 6 | 2¹⁹² ≈ 10⁵⁷ combinations |
| 21 | 224 | 7 | 2²²⁴ ≈ 10⁶⁷ combinations |
| 24 | 256 | 8 | 2²⁵⁶ ≈ 10⁷⁷ combinations |

### Checksum Validation

MetroVault validates all imported mnemonics:

```
Validation Steps:
1. Check word count is valid (multiple of 3)
2. Check all words exist in BIP-39 English wordlist
3. Extract entropy bits and checksum bits from word indices
4. Recompute SHA-256 checksum from extracted entropy
5. Compare computed checksum with extracted checksum
```

---

## Seed Derivation

### BIP-39 Mnemonic → Seed

The mnemonic phrase is converted to a 512-bit seed using PBKDF2:

```
┌─────────────────────────────────────────────────────────────────┐
│                    BIP-39 Seed Derivation                       │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Input:                                                         │
│    • Password: mnemonic words joined by spaces                  │
│    • Salt: "mnemonic" + optional_passphrase                     │
│                                                                 │
│  Algorithm: PBKDF2-HMAC-SHA512                                  │
│  Iterations: 2,048                                              │
│  Output Length: 64 bytes (512 bits)                             │
│                                                                 │
│  Result: 512-bit BIP-39 seed                                    │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### Optional Passphrase (BIP-39 Extension)

The passphrase provides an additional layer of security:

```
┌─────────────────────────────────────────────────────────────────┐
│                    Passphrase Benefits                          │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Same mnemonic + different passphrase = completely different    │
│  wallet with no mathematical relationship                       │
│                                                                 │
│  Use cases:                                                     │
│  • Plausible deniability (show empty wallet if coerced)         │
│  • Multi-wallet from single mnemonic backup                     │
│  • Additional brute-force protection                            │
│                                                                 │
│  Warning: Passphrase is NOT recoverable!                        │
│  Lost passphrase = lost funds forever                           │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

---

## BIP-32 Key Hierarchy

### Master Key Generation

The 512-bit seed is converted to a master extended private key:

```
┌─────────────────────────────────────────────────────────────────┐
│                  Master Key Derivation                          │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  I = HMAC-SHA512(Key="Bitcoin seed", Data=seed)                 │
│                                                                 │
│  I_L = first 256 bits  → Master Private Key                     │
│  I_R = last 256 bits   → Master Chain Code                      │
│                                                                 │
│  Together: Extended Master Private Key (xprv)                   │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### Derivation Paths

MetroVault supports standard BIP derivation paths:

| Address Type | BIP | Derivation Path | Prefix | Example Address |
|-------------|-----|-----------------|--------|-----------------|
| Legacy | BIP-44 | `m/44'/0'/0'` | `1...` | `1A1zP1eP5QGefi...` |
| Nested SegWit | BIP-49 | `m/49'/0'/0'` | `3...` | `3J98t1WpEZ73CN...` |
| Native SegWit | BIP-84 | `m/84'/0'/0'` | `bc1q...` | `bc1qar0srrr7xf...` |
| Taproot | BIP-86 | `m/86'/0'/0'` | `bc1p...` | `bc1p5d7rjq7g7r...` |

### Key Derivation Process

```
┌─────────────────────────────────────────────────────────────────┐
│                    Example: BIP-84 Path                         │
│                    m/84'/0'/0'/0/0                               │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  m          Master key (from seed)                              │
│   │                                                             │
│   └─ 84'    Purpose (BIP-84 Native SegWit) [hardened]          │
│       │                                                         │
│       └─ 0' Coin type (0 = Bitcoin mainnet) [hardened]         │
│           │                                                     │
│           └─ 0' Account index [hardened]                       │
│               │                                                 │
│               └─ 0  External chain (0=receive, 1=change)       │
│                   │                                             │
│                   └─ 0  Address index                          │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### Hardened vs Normal Derivation

```
┌─────────────────────────────────────────────────────────────────┐
│                 Hardened Derivation (')                         │
├─────────────────────────────────────────────────────────────────┤
│  • Uses parent private key in HMAC calculation                  │
│  • Cannot derive child keys from parent public key alone        │
│  • Marked with apostrophe (') or 'h' suffix                     │
│  • Used for coin type, purpose, and account levels              │
├─────────────────────────────────────────────────────────────────┤
│                  Normal Derivation                              │
├─────────────────────────────────────────────────────────────────┤
│  • Uses parent public key in HMAC calculation                   │
│  • Child public keys can be derived from parent public key      │
│  • Used for receive and change address derivation               │
│  • Enables watch-only wallets                                   │
└─────────────────────────────────────────────────────────────────┘
```

---

## Seed Storage

### Data Model

Wallet data is split into two categories:

```
┌─────────────────────────────────────────────────────────────────┐
│                     WalletMetadata                              │
│                   (Non-sensitive, display only)                 │
├─────────────────────────────────────────────────────────────────┤
│  • id: UUID                                                     │
│  • name: "My Wallet"                                            │
│  • derivationPath: "m/84'/0'/0'"                                │
│  • masterFingerprint: "a1b2c3d4"                                │
│  • hasPassphrase: true/false                                    │
│  • createdAt: timestamp                                         │
│                                                                 │
│  Storage: Plain JSON in EncryptedSharedPreferences              │
│  Encryption: Single layer (platform only)                       │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│                      WalletSecrets                              │
│                     (Highly sensitive)                          │
├─────────────────────────────────────────────────────────────────┤
│  • mnemonic: "abandon ability able..."                          │
│  • passphrase: "optional-passphrase"                            │
│                                                                 │
│  Storage: Session-key encrypted in EncryptedSharedPreferences   │
│  Encryption: Dual layer (session key + platform)                │
└─────────────────────────────────────────────────────────────────┘
```

### Encryption Layers

See [SECURITY.md](./SECURITY.md) for full encryption details.

```
Mnemonic ──► AES-256-GCM (Session Key) ──► AES-256-GCM (Keystore) ──► Disk
                    │                              │
            Password-derived               Hardware-backed
            (wiped on logout)              (survives restart)
```

---

## Seed Usage

### Wallet Operations Flow

```
┌─────────────────────────────────────────────────────────────────┐
│                    Wallet Open Flow                             │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  1. Load WalletSecrets from storage                             │
│     └─ Decrypt with session key                                 │
│                                                                 │
│  2. Parse mnemonic string → word list                           │
│                                                                 │
│  3. Derive 512-bit seed (PBKDF2, ~10ms)                         │
│     └─ MnemonicCode.toSeed(words, passphrase)                   │
│                                                                 │
│  4. Generate master extended private key                        │
│     └─ DeterministicWallet.generate(seed)                       │
│                                                                 │
│  5. Derive account-level keys                                   │
│     └─ masterKey.derivePrivateKey("m/84'/0'/0'")                │
│                                                                 │
│  6. Store keys in RAM (WalletState)                             │
│     └─ SecureByteArray for mnemonic                             │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### Address Generation

```
Account xpub ──► Derive /0/n ──► Public Key ──► Script ──► Address
                     │
              External (receive)
              or /1/n (change)
```

Each address type uses different script encoding:

| Type | Script | Address Format |
|------|--------|---------------|
| P2PKH | `OP_DUP OP_HASH160 <pubKeyHash> OP_EQUALVERIFY OP_CHECKSIG` | `1...` |
| P2SH-P2WPKH | `OP_HASH160 <scriptHash> OP_EQUAL` | `3...` |
| P2WPKH | `OP_0 <pubKeyHash>` | `bc1q...` |
| P2TR | `OP_1 <x-only pubkey>` | `bc1p...` |

### Transaction Signing

```
┌─────────────────────────────────────────────────────────────────┐
│                    PSBT Signing Flow                            │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  1. Parse PSBT from QR code                                     │
│                                                                 │
│  2. For each input:                                             │
│     a. Extract scriptPubKey                                     │
│     b. Search address lookup (100 receive + 100 change)         │
│     c. If match found:                                          │
│        - Derive signing key at that path                        │
│        - Sign input with private key                            │
│                                                                 │
│  3. Return signed PSBT as QR code                               │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

---

## Memory Security

### Secure Storage in RAM

```
┌─────────────────────────────────────────────────────────────────┐
│                    WalletState (In-Memory)                      │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  mnemonic:          SecureByteArray (wiped on close)            │
│  masterPrivateKey:  Native object (garbage collected)           │
│  accountPrivateKey: Native object (for signing)                 │
│  accountPublicKey:  Native object (for address generation)      │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### Key Wiping

On wallet close or app exit:

```kotlin
// Explicit memory wiping
mnemonicBytes.fill(0)       // Overwrite with zeros
secretKeyBytes.fill(0)      // Overwrite with zeros
references = null           // Clear references
System.gc()                 // Request garbage collection
```

### Session Lifecycle

```
App Start ──► No keys in memory
                   │
Login ─────────────┼──► Derive session key (~200ms)
                   │    └─ User password + stored salt
                   │
Open Wallet ───────┼──► Decrypt mnemonic
                   │    └─ Derive keys to RAM
                   │
Operations ────────┼──► Use cached keys (instant)
                   │
Close Wallet ──────┼──► Wipe WalletState from RAM
                   │
Logout ────────────┼──► Wipe session key
                   │
App Exit ──────────┴──► All keys cleared
```

---

## Summary

| Component | Algorithm | Security Level |
|-----------|-----------|---------------|
| Entropy Generation | SecureRandom + optional user entropy | Hardware-backed CSPRNG |
| Entropy Mixing | SHA-256(user + system) | Always cryptographically secure |
| Mnemonic Encoding | BIP-39 with SHA-256 checksum | 128-256 bit security |
| Seed Derivation | PBKDF2-HMAC-SHA512, 2048 iterations | Standard BIP-39 |
| Master Key Generation | HMAC-SHA512("Bitcoin seed") | Standard BIP-32 |
| Key Derivation | HMAC-SHA512 (hardened + normal) | Standard BIP-32 |
| Storage Encryption | AES-256-GCM (dual layer) | 256-bit symmetric |
| Memory Protection | Explicit wiping + SecureByteArray | Defense in depth |
