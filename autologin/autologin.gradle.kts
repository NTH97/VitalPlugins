version = "0.1.0"

project.extra["PluginName"]        = "Auto Login Test"
project.extra["PluginDescription"] = "Automatically logs in with saved credentials and TOTP"

tasks {
	jar {
		manifest {
			attributes(mapOf(
				"Plugin-Version"     to project.version,
				"Plugin-Id"          to project.name,
				"Plugin-Provider"    to project.extra["PluginProvider"],
				"Plugin-Description" to project.extra["PluginDescription"],
				"Plugin-License"     to project.extra["PluginLicense"]
			))
		}
	}
}
