import com.github.jengelman.gradle.plugins.shadow.tasks.*
import org.jetbrains.kotlin.gradle.plugin.mpp.*
import org.jetbrains.kotlin.gradle.tasks.*
import org.jetbrains.kotlin.konan.target.*

plugins {
    id("kotlin-multiplatform")
    id("kotlinx-serialization")
    id("com.epam.drill.cross-compilation")
    id("com.epam.drill.version.plugin")
    id("com.github.johnrengelman.shadow")
    distribution
    `maven-publish`
}

allprojects {
    repositories {
        mavenLocal()
        maven(url = "https://oss.jfrog.org/artifactory/list/oss-release-local")
        maven(url = "https://dl.bintray.com/kotlin/kotlinx/")
        maven(url = "https://dl.bintray.com/kotlin/ktor/")
        mavenCentral()
        jcenter()
    }

    apply(plugin = "com.epam.drill.version.plugin")
    tasks.withType<KotlinCompile> {
        kotlinOptions.allWarningsAsErrors = true
    }
    tasks.withType<KotlinNativeCompile> {
        kotlinOptions.allWarningsAsErrors = true
    }
    configurations.all {
        resolutionStrategy.force("org.jetbrains.kotlinx:kotlinx-coroutines-core-native:$coroutinesVersion")
    }

}
val libName = "drill-agent"

kotlin {
    setOf(
        mingwX64 { binaries.all { linkerOpts("-lpsapi", "-lwsock32", "-lws2_32", "-lmswsock") } },
        linuxX64(),
        macosX64()
    ).forEach { target ->
        target.compilations["test"]?.cinterops?.apply { create("jvmapiStub");create("testSocket") }
        target.binaries { sharedLib(libName, setOf(DEBUG)) }
    }

    crossCompilation {

        common {
            defaultSourceSet {
                dependsOn(sourceSets.named("commonMain").get())
                dependencies {
                    implementation("org.jetbrains.kotlinx:kotlinx-serialization-runtime-native:$serializationRuntimeVersion")
                    implementation("com.epam.drill:jvmapi-native:$drillJvmApiLibVersion")
                    implementation("com.epam.drill.transport:core:$drillTransportLibVerison")
                    implementation("com.benasher44:uuid:0.0.6")
                    implementation("com.epam.drill.interceptor:http:$drillHttpInterceptorVersion")
                    implementation("com.epam.drill:drill-agent-part:$drillApiVersion")
                    implementation("com.epam.drill:common:$drillApiVersion")
                    implementation("com.epam.drill.logger:logger:$drillLogger")
                    implementation("com.epam.drill.agent:agent:$drillAgentCoreVersion")
                }
            }
        }
    }

    jvm {
        compilations["main"].defaultSourceSet {
            dependencies {
                implementation(kotlin("stdlib"))
                implementation(kotlin("reflect")) //TODO jarhell quick fix for kotlin jvm apps
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-runtime:$serializationRuntimeVersion")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
                implementation("com.epam.drill:common-jvm:$drillApiVersion")
                implementation("com.epam.drill:drill-agent-part-jvm:$drillApiVersion")
                implementation("com.alibaba:transmittable-thread-local:2.11.0")
            }
        }
        compilations["test"].defaultSourceSet {
            dependencies {
                implementation(kotlin("test-junit"))
                implementation("org.junit.jupiter:junit-jupiter:5.5.2")
            }
        }
    }

    sourceSets {
        commonMain {
            dependencies {
                implementation("org.jetbrains.kotlin:kotlin-stdlib-common")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-runtime-common:$serializationRuntimeVersion")
                implementation("com.epam.drill:drill-agent-part:$drillApiVersion")
                implementation("com.epam.drill:common:$drillApiVersion")
            }
        }
        commonTest {
            dependencies {
                implementation("org.jetbrains.kotlin:kotlin-test-common")
                implementation("org.jetbrains.kotlin:kotlin-test-annotations-common")
            }
        }

    }

}

val jvmJar by tasks.getting(Jar::class) {
    from(provider {
        kotlin.jvm().compilations["main"].compileDependencyFiles.map { if (it.isDirectory) it else zipTree(it) }
    })
}

val agentShadow by tasks.registering(ShadowJar::class) {
    mergeServiceFiles()
    isZip64 = true
    archiveFileName.set("drillRuntime.jar")
    from(jvmJar)
    relocate("kotlin", "kruntime")
}

tasks.withType<KotlinNativeCompile> {
    kotlinOptions.freeCompilerArgs += "-Xuse-experimental=kotlinx.serialization.ImplicitReflectionSerializer"
    kotlinOptions.freeCompilerArgs += "-Xuse-experimental=kotlin.ExperimentalUnsignedTypes"
    kotlinOptions.freeCompilerArgs += "-Xuse-experimental=kotlin.time.ExperimentalTime"
    kotlinOptions.freeCompilerArgs += "-Xuse-experimental=kotlinx.coroutines.ExperimentalCoroutinesApi"
}

tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest> {
    testLogging.showStandardStreams = true
}

afterEvaluate {
    val availableTarget =
        kotlin.targets.filterIsInstance<KotlinNativeTarget>().filter { HostManager().isEnabled(it.konanTarget) }

    distributions {
        availableTarget.forEach {
            val name = it.name
            create(name) {
                distributionBaseName.set(name)
                contents {
                    from(tasks.getByPath(":proxy-agent:shadowJar"))
                    from(agentShadow)
                    from(tasks.getByPath("link${libName.capitalize()}DebugShared${name.capitalize()}"))
                }
            }
        }
    }
    publishing {
        repositories {
            maven {

                url = uri("http://oss.jfrog.org/oss-release-local")
                credentials {
                    username =
                        if (project.hasProperty("bintrayUser"))
                            project.property("bintrayUser").toString()
                        else System.getenv("BINTRAY_USER")
                    password =
                        if (project.hasProperty("bintrayApiKey"))
                            project.property("bintrayApiKey").toString()
                        else System.getenv("BINTRAY_API_KEY")
                }
            }
        }

        publications {
            availableTarget.forEach {
                create<MavenPublication>("${it.name}Zip") {
                    artifactId = "$libName-${it.name}"
                    artifact(tasks["${it.name}DistZip"])
                }
            }
        }
    }
}
