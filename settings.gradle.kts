pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex(".*google.*")
                includeGroupByRegex(".*android.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // miuix（ui/blur/icons/preference）均发布在 Maven Central，免鉴权；
        // miuix-glass 模块以源码形式 vendor 于 app/src/main/java/top/yukonga/miuix/kmp/glass。
        mavenLocal()
    }
}

rootProject.name = "Kiba"
include(":app")
