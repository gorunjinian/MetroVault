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

Uses Android's `SecureRandom`, which is backed by the kernel's CSPRNG:

```
┌─────────────────────────────────────────────────────────────────┐
│                     SecureRandom Sources                        │
├─────────────────────────────────────────────────────────────────┤
│  • Kernel CSPRNG (getrandom / /dev/urandom), read through       │
│    Conscrypt (BoringSSL), Android's default provider            │
│  • The kernel pool is seeded from interrupt timing and the      │
│    device's hardware RNG, where one is present                  │
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
│  Encoding: 1 byte per flip, in entry order                      │
│  Example: H,T,T,H → 0x00 0x01 0x01 0x00                         │
└─────────────────────────────────────────────────────────────────┘
```

### 3. Dice Roll Entropy

Casino-grade dice provide excellent physical randomness:

```
┌─────────────────────────────────────────────────────────────────┐
│                      Dice Roll Method                           │
├─────────────────────────────────────────────────────────────────┤
│  • Each roll (1-6) contributes ~2.58 bits (log₂(6))             │
│  • Encoding: 1 byte per roll (1-6), in entry order              │
│  • Example: 3,6,1 → 0x03 0x06 0x01                              │
│                                                                 │
│  12-word mnemonic: ~50 rolls (128 bits / 2.58 bits per roll)    │
│  24-word mnemonic: ~100 rolls                                   │
└─────────────────────────────────────────────────────────────────┘
```

Neither source is packed into bits. SHA-256 compresses the input during mixing (below), so every toss or roll reaches the hash, including a final partial byte of tosses or an odd last roll.

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

New wallets are created with 12 or 24 words; all five lengths pass validation.

### Checksum Validation

MetroVault validates all imported mnemonics:

```
Validation Steps:
1. Check word count is valid (12, 15, 18, 21 or 24)
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
| Silent Payments | BIP-352 | `m/352'/0'/0'` | `sp1q...` | `sp1qq...` |

The third level is the account number (`0'` for the first account). Testnet wallets use coin type `1'` instead of `0'` (e.g. `m/84'/1'/0'`, `tb1q...` addresses). A Silent Payments wallet derives its spend key at `…/0'/0` and its scan key at `…/1'/0` below the account path.

### Key Derivation Process

```
┌─────────────────────────────────────────────────────────────────┐
│                    Example: BIP-84 Path                         │
│                    m/84'/0'/0'/0/0                              │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  m          Master key (from seed)                              │
│   │                                                             │
│   └─ 84'    Purpose (BIP-84 Native SegWit) [hardened]           │
│       │                                                         │
│       └─ 0' Coin type (0 = Bitcoin mainnet) [hardened]          │
│           │                                                     │
│           └─ 0' Account index [hardened]                        │
│               │                                                 │
│               └─ 0  External chain (0=receive, 1=change)        │
│                   │                                             │
│                   └─ 0  Address index                           │
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
│  • hasPassphrase: true = passphrase asked for on every open     │
│  • keyIds: references to WalletKeys entries                     │
│  • createdAt: timestamp                                         │
│  • accounts, multisig config, silent-payment PUBLIC keys,       │
│    address-list preferences                                     │
│                                                                 │
│  Storage: Plain JSON in EncryptedSharedPreferences              │
│  Encryption: Single layer (platform only)                       │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│                       WalletKeys                                │
│                     (Highly sensitive)                          │
├─────────────────────────────────────────────────────────────────┤
│  • keyId: UUID                                                  │
│  • mnemonic: "abandon ability able..."                          │
│  • bip39Seed: 512-bit BIP-39 seed (hex)                         │
│  • fingerprint: "a1b2c3d4"                                      │
│  • label: "My Main Seed"                                        │
│                                                                 │
│  Storage: Session-key encrypted in EncryptedSharedPreferences   │
│  Encryption: Dual layer (session key + platform)                │
└─────────────────────────────────────────────────────────────────┘
```

The passphrase itself is **never stored**. `bip39Seed` holds the seed derived from the mnemonic plus the passphrase when a wallet using the key saves its passphrase, and the base seed (empty passphrase) otherwise. A wallet with `hasPassphrase = true` gets its seed from the mnemonic and the passphrase entered at open, kept in RAM only.

Keys are shared: wallets made from the same mnemonic + passphrase reference one `WalletKeys` entry by `keyId`, and the entry is deleted only when no wallet references it. See [SECURITY.md](./SECURITY.md#passphrase-wallets-sharing-a-key) for how the stored seed follows the wallets sharing a key.

### Encryption Layers

See [SECURITY.md](./SECURITY.md) for full encryption details.

```
WalletKeys ──► AES-256-GCM (Session Key) ──► AES-256-GCM (Keystore) ──► Disk
                      │                              │
              Password-derived               Hardware-backed
              (wiped on lock)                (survives restart)
```

---

## Seed Usage

### Wallet Operations Flow

```
┌─────────────────────────────────────────────────────────────────┐
│                    Wallet Open Flow                             │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  1. Load the wallet's WalletKeys entry from storage             │
│     └─ Decrypt with session key                                 │
│                                                                 │
│  2. Choose the seed                                             │
│     ├─ Session seed, if a passphrase was entered this visit     │
│     └─ Otherwise the stored bip39Seed, only if its fingerprint  │
│        matches the wallet's (else the passphrase is asked for)  │
│                                                                 │
│  3. Generate master extended private key                        │
│     └─ DeterministicWallet.generate(seed)                       │
│                                                                 │
│  4. Derive the active account's keys                            │
│     └─ masterKey.derivePrivateKey("m/84'/0'/0'")                │
│                                                                 │
│  5. Store keys in RAM (WalletState)                             │
│     └─ SecureByteArray for mnemonic                             │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

Opening a wallet runs no PBKDF2: the BIP-39 seed (`MnemonicCode.toSeed`) is computed once when the wallet is created, and again only when a passphrase is entered.

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
│  2. For each input not already finalized, find its key:         │
│     a. BIP-32 derivation info under this wallet's fingerprint   │
│        → derive the key at that path (if it does not match,     │
│          retry the standard purposes and accounts)              │
│     b. Otherwise match the scriptPubKey against the wallet's    │
│        addresses (2,000 receive + 2,000 change)                 │
│                                                                 │
│  3. Sign under the signing policy                               │
│     • SIGHASH_ALL or SIGHASH_DEFAULT only                       │
│     • Multi-input segwit v0 spends need previous transactions   │
│                                                                 │
│  4. Return signed PSBT as QR code                               │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

Inputs that match this wallet but break the policy are reported with the reason. When a multi-input PSBT lacks previous transactions, every signature is withheld unless the user chooses "Sign Anyway": without them the fee cannot be verified (the BIP-143 fee attack, CVE-2020-14199). A single-input spend signs from its witness UTXO, because the attack needs two inputs.

---

## Memory Security

### Secure Storage in RAM

```
┌─────────────────────────────────────────────────────────────────┐
│                    WalletState (In-Memory)                      │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  mnemonic:          SecureByteArray (zeroed on wipe)            │
│  masterPrivateKey:  JVM object (reference dropped on wipe)      │
│  accountPrivateKey: JVM object (for signing)                    │
│  accountPublicKey:  JVM object (for address generation)         │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

The key objects come from vaultovich, which is pure Kotlin/JVM. They are immutable and cannot be zeroed in place, so wiping drops every reference and leaves them to the garbage collector.

### Key Wiping

On returning Home, locking, or the app going to the background, every loaded `WalletState` is wiped:

```kotlin
// WalletState.wipe()
mnemonic?.close()           // SecureByteArray overwrites its bytes with zeros
mnemonic = null
masterPrivateKey = null     // Drop key references
accountPrivateKey = null
accountPublicKey = null
System.gc()                 // Request garbage collection
```

Transient secrets are zeroed in `finally` blocks once used: the system and mixed entropy buffers in `MnemonicService`, and the BIP-39 seed bytes as soon as the master key exists, whether the wallet is created from its mnemonic (`BitcoinService.createWalletFromMnemonic`) or opened from its stored seed (`createWalletFromSeed`). The master key is generated with vaultovich's `ByteArray` overload, which hashes the seed in place. The hex string the stored seed is read from cannot be zeroed, because JVM strings are immutable; only its decoded bytes are.

### Session Lifecycle

```
App Start ──► No keys in memory
                   │
Unlock ────────────┼──► Derive session key (PBKDF2 → HKDF)
                   │    └─ User password + stored salt
                   │       (600,000 PBKDF2 iterations for new passwords)
                   │
Open Wallet ───────┼──► Decrypt WalletKeys
                   │    └─ Derive keys to RAM
                   │
Operations ────────┼──► Use cached keys (instant)
                   │
Return Home ───────┼──► Wipe WalletState and session seeds from RAM
                   │
Lock / Background ─┴──► Wipe session key and all keys
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
| Storage Encryption | AES-256-GCM (dual layer for keys, single for metadata) | 256-bit symmetric |
| Memory Protection | Explicit wiping + SecureByteArray | Defense in depth |
