import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent

plugins {
    id("idea")
    id("java")
    id("maven-publish")
}

repositories {
    mavenCentral()
}

// gradle.properties
val jUnitVersion: String by extra
val minecraftVersion: String by extra
val modId: String by extra
val modJavaVersion: String by extra
val modName: String by extra
val quantifiedIntegrationDisplayName: String by extra
val quantifiedVersion: String by extra
val specificationVersion: String by extra

val quantifiedJars = rootProject.fileTree(rootProject.file("libs")) {
	include("quantified*omni*${quantifiedVersion}.jar", "quantified-${quantifiedVersion}.jar")
}.files
if (quantifiedJars.isEmpty()) {
	throw GradleException("Missing Quantified API jar in libs (expected quantified*omni*${quantifiedVersion}.jar)")
}
val quantifiedJar = quantifiedJars.sortedBy { it.name }.last()

dependencies {
	compileOnly(files(quantifiedJar))
    implementation(
        group = "com.google.guava",
        name = "guava",
        version = "31.1-jre"
    )
    implementation(
        group = "org.jetbrains",
        name = "annotations",
        version = "23.0.0"
    )
    implementation(
        group = "it.unimi.dsi",
        name = "fastutil",
        version = "8.5.6"
    )
    implementation(
        group = "org.apache.logging.log4j",
        name = "log4j-api",
        version = "2.17.0"
    )
    testImplementation(
        group = "org.junit.jupiter",
        name = "junit-jupiter-api",
        version = jUnitVersion
    )
    testRuntimeOnly(
        group = "org.junit.jupiter",
        name = "junit-jupiter-engine",
        version = jUnitVersion
    )
}

val quantifiedIntegrationGeneratedSources = layout.buildDirectory.dir("generated/sources/quantifiedIntegration/java")

val generateQuantifiedIntegrationBuildInfo by tasks.registering {
	inputs.property("modId", providers.gradleProperty("modId"))
	inputs.property("modName", providers.gradleProperty("modName"))
	inputs.property("quantifiedIntegrationDisplayName", providers.gradleProperty("quantifiedIntegrationDisplayName"))
    inputs.property("specificationVersion", providers.gradleProperty("specificationVersion"))
    outputs.dir(quantifiedIntegrationGeneratedSources)
    val outDir = quantifiedIntegrationGeneratedSources
    doLast {
        val outputFile = outDir.get()
            .file("mezz/jei/core/QuantifiedIntegration/QuantifiedIntegrationBuildInfo.java")
            .asFile
        outputFile.parentFile.mkdirs()
        val modId = inputs.properties["modId"] as String
        val displayName = inputs.properties["quantifiedIntegrationDisplayName"] as String
        val version = inputs.properties["specificationVersion"] as String
        outputFile.writeText("""
            package mezz.jei.core.QuantifiedIntegration;

            public final class QuantifiedIntegrationBuildInfo {
                public static final String MOD_ID = "$modId";
                public static final String DISPLAY_NAME = "$displayName";
            	public static final String VERSION = "$version";

            	private QuantifiedIntegrationBuildInfo() {
            	}
            }
        """.trimIndent())
    }
}

sourceSets {
    named("main") {
        java.srcDir(quantifiedIntegrationGeneratedSources)
        //The Core has no resources
        resources.setSrcDirs(emptyList<String>())
    }
    named("test") {
        //The test module has no resources
        resources.setSrcDirs(emptyList<String>())
    }
}

tasks.named<Test>("test") {
    useJUnitPlatform()
    include("mezz/jei/test/**")
    exclude("mezz/jei/test/lib/**")
    outputs.upToDateWhen { false }
    testLogging {
        events = setOf(TestLogEvent.FAILED)
        exceptionFormat = TestExceptionFormat.FULL
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(modJavaVersion))
    }
    withSourcesJar()
}

tasks.withType<JavaCompile> {
	dependsOn(generateQuantifiedIntegrationBuildInfo)
    options.encoding = "UTF-8"
    javaToolchains {
        compilerFor {
            languageVersion.set(JavaLanguageVersion.of(modJavaVersion))
        }
    }
}

val sourcesJarTask = tasks.named<Jar>("sourcesJar") {
    dependsOn(generateQuantifiedIntegrationBuildInfo)
}

val baseArchivesName = "${modId}-${minecraftVersion}-core"
base {
    archivesName.set(baseArchivesName)
}

artifacts {
    archives(tasks.jar.get())
    archives(sourcesJarTask.get())
}

publishing {
    publications {
        register<MavenPublication>("coreJar") {
            artifactId = baseArchivesName
            artifact(tasks.jar.get())
            artifact(sourcesJarTask.get())
        }
    }
    repositories {
        val deployDir = project.findProperty("DEPLOY_DIR")
        if (deployDir != null) {
            maven(deployDir)
        }
    }
}

idea {
    module {
        for (fileName in listOf("build", "run", "out", "logs")) {
            excludeDirs.add(file(fileName))
        }
    }
}

