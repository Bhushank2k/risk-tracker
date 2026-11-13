plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

kotlin { jvmToolchain(17) }

application { mainClass.set("com.risktracker.cloud.CloudScannerMainKt") }

dependencies {
    implementation(project(":scanner-core"))
    implementation("org.json:json:20250517")
    testImplementation("junit:junit:4.13.2")
}
