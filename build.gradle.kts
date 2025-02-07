// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    val agpVersion = "8.9.0-rc01"
    id("com.android.application") version agpVersion apply false
    id("com.android.library") version agpVersion apply false
    kotlin("android") version "2.0.21" apply false
}