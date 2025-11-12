import org.gradle.api.tasks.wrapper.Wrapper.DistributionType

plugins {
    id("com.android.application") version "8.6.1" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("com.google.gms.google-services") version "4.4.2" apply false
}

allprojects {
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        kotlinOptions {
            jvmTarget = "17"
            freeCompilerArgs = freeCompilerArgs + listOf("-Xcontext-receivers")
        }
    }
}

tasks.register("wrapper", Wrapper::class) {
    gradleVersion = "8.10.2"
    distributionType = DistributionType.ALL
}
