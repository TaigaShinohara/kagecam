// NOTE (build-environment workaround): this VM's egress proxy resets Java TLS
// connections, so dependencies are resolved through a local curl-backed mirror
// (~/workspace/tools/mirror.py). On a normal dev machine, replace these with
// google() / mavenCentral() / gradlePluginPortal().
pluginManagement {
    repositories {
        maven { url = uri("http://127.0.0.1:18080/google"); isAllowInsecureProtocol = true }
        maven { url = uri("http://127.0.0.1:18080/central"); isAllowInsecureProtocol = true }
        maven { url = uri("http://127.0.0.1:18080/plugins"); isAllowInsecureProtocol = true }
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("http://127.0.0.1:18080/google"); isAllowInsecureProtocol = true }
        maven { url = uri("http://127.0.0.1:18080/central"); isAllowInsecureProtocol = true }
    }
}
rootProject.name = "KageCam"
include(":app")
