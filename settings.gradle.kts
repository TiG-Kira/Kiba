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
        // miuix（ui/blur/glass/material/icons/preference）均发布在 Maven Central，免鉴权；
        // Kiba 定制顶栏家族源码在 app/src/main/java/com/kira/kiba/ui/material/glass。
        mavenLocal()
    }
}

rootProject.name = "Kiba"
include(":app")
include(":core-host")

// ── 可选本地模块 ──
// 通过 local.properties 的 `kiba.extraModules=<路径>=<模块名>[,...]` 注入仓库外的
// 本地 library 模块（路径相对本文件）。未配置时为空操作，构建产物与行为完全不变。
// 仅用于作者本地的私有扩展；公开仓库不包含也不引用任何具体模块。
run {
    val extras = java.util.Properties()
    val localProps = rootDir.resolve("local.properties")
    if (localProps.exists()) {
        localProps.inputStream().use { extras.load(it) }
    }
    val declared = extras.getProperty("kiba.extraModules")?.trim().orEmpty()
    if (declared.isNotEmpty()) {
        declared.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { entry ->
            val parts = entry.split('=')
            if (parts.size == 2) {
                val (path, name) = parts
                val dir = rootDir.resolve(path)
                if (dir.resolve("build.gradle.kts").exists() || dir.resolve("build.gradle").exists()) {
                    include(":$name")
                    project(":$name").projectDir = dir
                }
            }
        }
    }
}
