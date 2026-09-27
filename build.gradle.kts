plugins {
    // AGP 8.6.1 = Default-Build-Tools 34.0.0 (lokal installiert) und benötigt
    // Gradle >= 8.7. KGP 2.4.20 verlangt AGP >= 8.5.2 (Kompatibilitätsmatrix),
    // daher der Sprung von AGP 8.2.0.
    id("com.android.application") version "8.6.1" apply false
    // Kotlin 2.4.20 ist zwingend: LiteRT-LM 0.17.1 (app/libs) ist mit
    // Kotlin-Metadaten mv=2.4.0 kompiliert; Kotlin 1.9.20 kann max. mv=2.0.0
    // lesen und lehnt das AAR mit "unresolved reference" ab.
    id("org.jetbrains.kotlin.android") version "2.4.20" apply false
    // Ab Kotlin 2.0 ist der Compose-Compiler ein eigenes Plugin und ersetzt
    // `composeOptions.kotlinCompilerExtensionVersion`. Version == Kotlin-Version.
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
}
