plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.example.mpesaboard"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.mpesaboard"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        // Supabase project URL and the anon / publishable key (safe to ship; never the service_role key).
 buildConfigField("String", "SUPABASE_URL", "\"https://xsdwxnlhfyplzilxzznq.supabase.co/rest/v1/\"")
        buildConfigField("String", "SUPABASE_KEY", "\"eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InhzZHd4bmxoZnlwbHppbHh6em5xIiwicm9sZSI6ImFub24iLCJpYXQiOjE3OTI4NTI4MTEsImV4cCI6MjEwODQyODgxMX0.JAIbExqyixPCbgD8sWU0DoIyFBpdh4WmNLqhTGRBQFY\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
