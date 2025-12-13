# MetroVault

<p align="center">
  <img src="app/src/main/res/drawable/app_logo.png" alt="MetroVault Logo" width="120">
</p>

<p align="center">
  <strong>Turn your Android phone into a secure Bitcoin hardware wallet and signing device</strong>
</p>

<p align="center">
  <img src="Screenshot_1.png" alt="Screenshot 1" width="200">
  <img src="Screenshot_2.png" alt="Screenshot 2" width="200">
  <img src="Screenshot_3.png" alt="Screenshot 3" width="200">
  <img src="Screenshot_4.png" alt="Screenshot 4" width="200">
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android-green.svg" alt="Platform">
  <img src="https://img.shields.io/badge/API-26%2B-brightgreen.svg" alt="API">
  <img src="https://img.shields.io/badge/Language-Kotlin-purple.svg" alt="Kotlin">
  <img src="https://img.shields.io/badge/UI-Jetpack%20Compose-blue.svg" alt="Compose">
</p>

---

**MetroVault** is a secure, offline Android signing device application designed to turn your Android phone into a cold storage hardware wallet. Built with modern Android technologies and a custom Kotlin Bitcoin library, it prioritizes security, simplicity, and user experience.

## 🎯 Why MetroVault?

The primary goal of MetroVault is to provide a completely **offline** environment for managing Bitcoin private keys. It acts as a signer for your watch-only wallets (like BlueWallet, Sparrow, or Electrum) running on online devices. By keeping your keys on a device that never connects to the internet (air-gapped), you significantly reduce the attack surface for theft and malware.

**Key Benefits:**
- 💰 **Free** - Use any Android phone as a hardware wallet
- 🔒 **Secure** - Air-gapped operation with military-grade encryption
- 🔨 **Open Source** - Fully auditable code, no hidden backdoors
- 🎨 **Modern** - Beautiful Material 3 design with Jetpack Compose

## ✨ Features

### 🛡️ Security First
| Feature | Description |
|---------|-------------|
| **Air-Gapped Operation** | Designed for devices with no internet (Airplane mode, WiFi/Bluetooth disabled) |
| **Dual-Layer Encryption** | AES-256-GCM with PBKDF2 (210k iterations) + Android Keystore |
| **Biometric Authentication** | Hardware-backed fingerprint/face unlock with crypto binding |
| **Plausible Deniability** | Separate "Main" and "Decoy" wallets with different passwords |
| **Brute-Force Protection** | Exponential backoff rate limiting with 24-hour lockout |
| **Automatic Wipe** | Optional feature to completely wipe sensitive data after 3 unsuccessful login attempts |

### 💼 Wallet Management
- **Multi-Type Support**: Native SegWit (`bc1q...`), Taproot (`bc1p...`), Nested SegWit (`3...`), Legacy (`1...`)
- **BIP-39 Mnemonics**: Standard 12 or 24-word seed phrases
- **Passphrase Support**: Optional BIP-39 passphrase with choice to save locally or keep in session memory only
- **Custom Entropy**: Add your own randomness via dice rolls or coin flips
- **Mnemonic Tools**: Built-in checksum calculator and validator

### 📝 Transaction Signing
- **PSBT Workflow** (BIP-174): Partially Signed Bitcoin Transactions
- **QR Code Air-Gap**: Scan PSBT → Verify → Sign → Export via QR
- **Address Verification**: Confirm receive/change addresses on trusted screen
- **XPUB Export**: Generate watch-only wallets on your online device

### 📖 Signing / Verifying Messages
- **Sign Messages**: Sign messages to prove ownership
- **Verify Signatures**: Verify signatures and messages
- **Qr Code Support**: Sign messages using QR codes, and display signature via QR codes.

## 🔄 How It Works

```
┌──────────────────┐          QR Code          ┌──────────────────┐
│   ONLINE DEVICE  │◄─────────────────────────►│  METROVAULT      │
│   (Watch-Only)   │                           │  (Air-Gapped)    │
├──────────────────┤                           ├──────────────────┤
│ • Create PSBT    │  ──── Unsigned PSBT ───►  │ • Verify details │
│ • Broadcast TX   │  ◄──── Signed PSBT ────   │ • Sign with key  │
│ • View balance   │                           │ • Never online   │
└──────────────────┘                           └──────────────────┘
```

1. **Setup**: Import your XPUB from MetroVault to create a watch-only wallet on your online device
2. **Receive**: Generate addresses on either device (they'll match!)
3. **Send**: Create unsigned transaction (PSBT) on online device → Display as QR
4. **Sign**: Scan QR on MetroVault → Verify → Sign → Display signed PSBT QR
5. **Broadcast**: Scan signed QR on online device → Broadcast to network

## 🛠️ Tech Stack

| Category | Technology |
|----------|------------|
| **UI Framework** | Jetpack Compose (Material 3) |
| **Architecture** | MVVM with Clean Architecture |
| **Cryptography** | Custom Kotlin Bitcoin Library (Secp256k1, BIP-32, BIP-39, BIP-174) |
| **Storage** | EncryptedSharedPreferences (Android Keystore) |
| **QR Codes** | ZXing + URKit (Animated QR for large data) |
| **Biometrics** | AndroidX Biometric Library (BIOMETRIC_STRONG) |
| **Min SDK** | Android 8.0 (API 26) |

## 🚀 Installation

### Option 1: Build from Source (Recommended)

**Prerequisites:**
- Android Studio Ladybug or newer
- JDK 17
- Android device (API 26+)

**Steps:**
```bash
# Clone the repository
git clone https://github.com/gorunjinian/MetroVault.git

# Open in Android Studio
# Sync Gradle project
# Build
# Install on your air-gapped device
```

### Option 2: Download APK

Check the [Releases](https://github.com/gorunjinian/MetroVault/releases) page for pre-built APKs.

> ⚠️ **Security Note:** For production use, always build from source and install on a factory-reset device that remains permanently offline.

### Recommended Device Setup

For maximum security, use a dedicated device:

```
1. Factory reset before installation
2. Skip Google account setup (offline only)
3. Enable Airplane mode permanently  
4. Disable WiFi, Bluetooth, NFC, Mobile data
5. Remove SIM card
6. Disable USB debugging
```

## 📖 Documentation

Detailed documentation is available in the repository:

| Document | Description |
|----------|-------------|
| [SECURITY.md](./SECURITY.md) | Complete security architecture and encryption model |
| [SEED_GENERATION.md](./SEED_GENERATION.md) | How wallets are generated, stored, and used |

## 🤝 Contributing

Contributions are welcome! Please feel free to submit issues and pull requests.

### Reporting Issues

- 🐛 **Bugs**: Open a GitHub issue with reproduction steps
- 💡 **Features**: Open a discussion or issue with your proposal
- 🔒 **Security**: See [SECURITY.md](./SECURITY.md) for responsible disclosure

## 🙏 Acknowledgments

- [Bitcoin](https://bitcoin.org) - The protocol that makes this all possible
- [ACINQ](https://github.com/ACINQ/bitcoin-kmp) - Custom Bitcoin library from ACINQ's implementation
- [Jetpack Compose](https://developer.android.com/jetpack/compose) - Modern Android UI toolkit
- [ZXing](https://github.com/zxing/zxing) - QR code generation and scanning
- The open-source Bitcoin community for BIP standards

---

## ⚠️ Disclaimer

**MetroVault is currently in active development.**

While every effort is made to ensure security and correctness, using early-development software for managing real funds carries risks:

- ⚡ Always verify addresses on the device screen
- 🧪 Test with small amounts first
- 📝 **You are responsible for your own keys** - ensure proper mnemonic backups
- 🔍 Review the code yourself or have it audited before trusting with significant funds

---

<p align="center">
  Made with ❤️ for Bitcoin by Gorun Jinian
</p>
