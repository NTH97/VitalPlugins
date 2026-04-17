plugins {
	`java-library`
}

subprojects {
	apply<JavaPlugin>()
	apply(plugin = "java-library")

	group = "net.vital.plugins"

	project.extra["PluginProvider"]     = "Vital"
	project.extra["ProjectSupportUrl"]  = "https://discord.gg/dx9y7uc3rf"
	project.extra["PluginLicense"]      = "BSD 2-Clause License"

	repositories {
		mavenLocal()
		mavenCentral()
		maven("https://repo.runelite.net")
		maven {
			name = "GitHubPackagesVitalAPI"
			url = uri("https://maven.pkg.github.com/Vitalflea/VitalAPI")
			credentials {
				username = System.getenv("GITHUB_ACTOR")
				password = System.getenv("GITHUB_TOKEN")
			}
		}
		maven {
			name = "GitHubPackagesVitalShell"
			url = uri("https://maven.pkg.github.com/Vitalflea/VitalShell")
			credentials {
				username = System.getenv("GITHUB_ACTOR")
				password = System.getenv("GITHUB_TOKEN")
			}
		}
	}

	dependencies {
		compileOnly(rootProject.libs.runelite.api)
		compileOnly(rootProject.libs.runelite.client)
		compileOnly(rootProject.libs.vital.api)
		compileOnly(rootProject.libs.guice)
		compileOnly(rootProject.libs.javax.annotation)
		compileOnly(rootProject.libs.lombok)
		compileOnly(rootProject.libs.pf4j)

		annotationProcessor(rootProject.libs.lombok)
		annotationProcessor(rootProject.libs.pf4j)
	}

	java {
		sourceCompatibility = JavaVersion.VERSION_11
		targetCompatibility = JavaVersion.VERSION_11
	}

	tasks {
		withType<JavaCompile> {
			options.encoding = "UTF-8"
		}

		withType<AbstractArchiveTask> {
			isPreserveFileTimestamps = false
			isReproducibleFileOrder  = true
			dirMode  = 493
			fileMode = 420
		}
	}
}
