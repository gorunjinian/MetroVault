package com.gorunjinian.metrovault.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Data class representing a library/dependency used in the app.
 */
private data class Library(
    val name: String,
    val category: String,
    val description: String,
    val version: String? = null
)

/**
 * Screen displaying all libraries and dependencies used in the app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibUsedScreen(
    onBack: () -> Unit
) {
    val libraries = listOf(
        // Core Android
        Library("AndroidX Core KTX", "Core Android", "Kotlin extensions for Android core", "1.17.0"),
        Library("AndroidX AppCompat", "Core Android", "Backward-compatible Android components", "1.7.1"),
        Library("AndroidX Activity KTX", "Core Android", "Kotlin extensions for Activity", "1.12.2"),
        Library("AndroidX Fragment KTX", "Core Android", "Kotlin extensions for Fragment", "1.8.9"),
        Library("AndroidX Splash Screen", "Core Android", "Modern splash screen API", "1.2.0"),
        
        // UI & Design
        Library("Material Design 3", "UI & Design", "Google's Material Design components", "1.13.0"),
        Library("Jetpack Compose", "UI & Design", "Modern declarative UI toolkit", "BOM 2025.12.01"),
        Library("Compose Material Icons", "UI & Design", "Extended Material Design icons"),
        Library("Navigation Compose", "UI & Design", "Navigation for Compose apps", "2.9.6"),
        
        // Security & Crypto
        Library("AndroidX Security Crypto", "Security", "Encrypted SharedPreferences", "1.1.0"),
        Library("AndroidX Biometric", "Security", "Fingerprint & face authentication", "1.2.0-α05"),
        Library("Secp256k1 KMP", "Cryptography", "Bitcoin elliptic curve cryptography", "0.22.0"),
        
        // QR Code
        Library("ZXing Core", "QR Code", "Barcode/QR code processing library", "3.5.4"),
        Library("ZXing Android Embedded", "QR Code", "Android camera integration for ZXing", "4.3.0"),
        
        // Data & Serialization
        Library("CBOR", "Data", "Concise Binary Object Representation", "0.9"),
        
        // Concurrency
        Library("Kotlin Coroutines", "Concurrency", "Asynchronous programming support", "1.10.2"),
        
        // Lifecycle
        Library("AndroidX Lifecycle Runtime", "Lifecycle", "Lifecycle-aware components", "2.10.0"),
        Library("AndroidX Lifecycle ViewModel", "Lifecycle", "MVVM architecture support", "2.10.0"),
        Library("AndroidX Lifecycle Process", "Lifecycle", "App lifecycle observer", "2.10.0"),
        
        // Other
        Library("AndroidX Window", "Utilities", "Window management APIs", "1.5.1")
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Libraries Used") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            items(libraries) { library ->
                LibraryCard(library = library)
            }
            
            // Footer with count
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "${libraries.size} libraries • All open source",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp, horizontal = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun LibraryCard(library: Library) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = library.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = library.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            Column(
                horizontalAlignment = Alignment.End
            ) {
                // Category badge
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = library.category,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                
                // Version (if present)
                library.version?.let { version ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = version,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
