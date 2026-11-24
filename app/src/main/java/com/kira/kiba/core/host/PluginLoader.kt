package com.kira.kiba.core.host

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.util.Xml
import dalvik.system.DexClassLoader
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.zip.ZipInputStream

/**
 * 插件包安装与动态加载（设置页「插件」入口）。
 *
 * 插件包格式（zip）：
 * - `plugin.xml`   清单：id / name / version / summary / entry（入口类全名）/ signature（可选）
 * - `classes.dex`  插件代码（自身代码；宿主已有的依赖走 compileOnly 由运行时提供）
 * - `lib/<abi>/`   插件自有 native 库（libarm64-v8a 下的 .so 文件，可选）
 * - `Readme.md`    开发者说明（宿主不解析）
 *
 * 信任分级：
 * - `<signature>` 为官方（Kira / Kira Studio）对
 *   `id|version|entry|sha256(classes.dex)` 的 SHA256withECDSA 签名且公钥校验通过
 *   → trusted 插件：开放全部挂接点与完整 HostServices。
 * - 无签名或验签失败 → 社区插件：数据流类挂接点不参与，HostServices 只注入只读裁剪版。
 *
 * 加载机制：解压到应用私有目录后用 [DexClassLoader] 加载（parent = 宿主 classloader，
 * 双亲委派使宿主类天然共享）；入口类必须无参构造且实现 [AppExtensions.Provider]。
 */
object PluginLoader {

    /** 安装失败（带可读原因，UI 直接展示）。 */
    class InstallException(message: String) : Exception(message)

    /**
     * 官方插件签名公钥（EC P-256，X.509 SubjectPublicKeyInfo，Base64）。
     * 由 tools/signing/gen_keys.py 生成官方密钥对后填入。
     * 空串 = 官方校验未配置（所有插件一律按社区插件对待）。
     */
    private const val OFFICIAL_PLUGIN_PUBKEY_B64 =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEnpsLDhuhVK972v3lHVXV6UY3/jJqfml4PJTZts5dUYZ5gNoG7k9wVxHhqUM3ZXkPVK1e2G3Sy8ZeTM2iE1kJAg=="

    /** 安装包解压总大小上限（防 zip 炸弹）。 */
    private const val MAX_UNZIP_BYTES = 256L * 1024 * 1024

    private class Manifest(
        val id: String,
        val name: String,
        val version: String,
        val summary: String,
        val entry: String,
        val signature: String,
    )

    private fun pluginsRoot(context: Context): File = File(context.filesDir, "plugins")

    // ==================== 安装 ====================

    /**
     * 从系统文件选择器返回的 [uri] 安装插件；成功返回插件元信息，
     * 失败抛 [InstallException]（UI 层提示用户）。
     */
    fun install(context: Context, uri: Uri, app: Application): AppExtensions.PluginInfo {
        val tmp = File(context.cacheDir, "plugin_install_${System.currentTimeMillis()}")
        try {
            extractZip(context, uri, tmp)
            val root = locateRoot(tmp)
                ?: throw InstallException("安装失败：插件包内未找到 plugin.xml 清单文件")
            val manifest = parseManifest(File(root, "plugin.xml"))
            if (!File(root, "classes.dex").isFile) {
                throw InstallException("安装失败：插件包缺少 classes.dex")
            }
            val trusted = verifyOfficial(root, manifest)
            // 覆盖安装：同 id 先注销旧实例并清空旧目录
            val dest = File(pluginsRoot(context), manifest.id)
            AppExtensions.unregister(manifest.id)
            if (dest.exists()) dest.deleteRecursively()
            dest.mkdirs()
            copyDir(root, dest)
            loadAndRegister(context, dest, manifest, app, trusted)
            return AppExtensions.PluginInfo(
                id = manifest.id,
                name = manifest.name,
                version = manifest.version,
                summary = manifest.summary,
                trusted = trusted,
            )
        } catch (e: InstallException) {
            throw e
        } catch (e: Exception) {
            throw InstallException("安装失败：${e.message ?: "插件包无法读取"}")
        } finally {
            tmp.deleteRecursively()
        }
    }

    /** 卸载插件（注销运行时实例 + 删除安装目录）。 */
    fun uninstall(context: Context, id: String) {
        AppExtensions.unregister(id)
        File(pluginsRoot(context), id).deleteRecursively()
    }

    // ==================== 启动加载 ====================

    /** Application.onCreate 调用：批量加载已安装插件。单个损坏插件不影响其他插件。 */
    fun loadInstalled(app: Application) {
        val root = pluginsRoot(app)
        val dirs = root.listFiles { f -> f.isDirectory } ?: return
        for (dir in dirs) {
            try {
                val manifest = parseManifest(File(dir, "plugin.xml"))
                val trusted = verifyOfficial(dir, manifest)
                loadAndRegister(app, dir, manifest, app, trusted)
            } catch (_: Exception) {
                // 损坏插件跳过：宿主行为不变（app 侧可提供卸载入口）
            }
        }
    }

    // ==================== 加载 ====================

    private fun loadAndRegister(
        context: Context,
        dir: File,
        manifest: Manifest,
        app: Application,
        trusted: Boolean,
    ) {
        val dex = File(dir, "classes.dex")
        val optDir = File(context.codeCacheDir, "plugin_dexopt/${manifest.id}").apply { mkdirs() }
        val libDir = File(dir, "lib/${Build.SUPPORTED_ABIS[0]}")
        val loader = DexClassLoader(
            dex.absolutePath,
            optDir.absolutePath,
            if (libDir.isDirectory) libDir.absolutePath else null,
            app.classLoader,
        )
        val cls = try {
            loader.loadClass(manifest.entry)
        } catch (e: Throwable) {
            throw InstallException("安装失败：入口类 ${manifest.entry} 无法加载（${e.message ?: "未知错误"}）")
        }
        val provider = try {
            cls.getDeclaredConstructor().newInstance()
        } catch (e: Throwable) {
            throw InstallException("安装失败：入口类 ${manifest.entry} 缺少无参构造")
        }
        if (provider !is AppExtensions.Provider) {
            throw InstallException("安装失败：入口类未实现 Provider 插件接口")
        }
        AppExtensions.register(provider, app, trusted)
    }

    // ==================== 清单与签名 ====================

    private fun parseManifest(file: File): Manifest {
        if (!file.isFile) throw InstallException("安装失败：插件包内未找到 plugin.xml 清单文件")
        val parser = Xml.newPullParser()
        var id = ""
        var name = ""
        var version = ""
        var summary = ""
        var entry = ""
        var signature = ""
        try {
            file.inputStream().use { input ->
                parser.setInput(input, null)
                var event = parser.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    if (event == XmlPullParser.START_TAG) {
                        when (parser.name) {
                            "id" -> id = parser.nextText().trim()
                            "name" -> name = parser.nextText().trim()
                            "version" -> version = parser.nextText().trim()
                            "summary" -> summary = parser.nextText().trim()
                            "entry" -> entry = parser.nextText().trim()
                            "signature" -> signature = parser.nextText().trim()
                        }
                    }
                    event = parser.next()
                }
            }
        } catch (e: Exception) {
            throw InstallException("安装失败：plugin.xml 解析错误（${e.message ?: "格式无效"}）")
        }
        if (id.isBlank() || entry.isBlank() || name.isBlank()) {
            throw InstallException("安装失败：plugin.xml 缺少必填字段（id / name / entry）")
        }
        if (!id.matches(Regex("[A-Za-z0-9._-]+"))) {
            throw InstallException("安装失败：插件 id 只允许字母、数字、点、下划线与连字符")
        }
        return Manifest(id, name, version.ifBlank { "0.0.0" }, summary, entry, signature)
    }

    /** 官方签名校验（公钥未配置 / 无签名 / 验签失败 → false，一律按社区插件处理）。 */
    private fun verifyOfficial(root: File, manifest: Manifest): Boolean {
        if (OFFICIAL_PLUGIN_PUBKEY_B64.isBlank() || manifest.signature.isBlank()) return false
        return try {
            val dexSha = MessageDigest.getInstance("SHA-256").digest(File(root, "classes.dex").readBytes())
                .joinToString("") { "%02x".format(it) }
            val canonical = "${manifest.id}|${manifest.version}|${manifest.entry}|$dexSha"
            val kf = KeyFactory.getInstance("EC")
            val pub = kf.generatePublic(X509EncodedKeySpec(Base64.decode(OFFICIAL_PLUGIN_PUBKEY_B64, Base64.DEFAULT)))
            val sig = Signature.getInstance("SHA256withECDSA")
            sig.initVerify(pub)
            sig.update(canonical.toByteArray(Charsets.UTF_8))
            sig.verify(Base64.decode(manifest.signature, Base64.DEFAULT))
        } catch (_: Exception) {
            false
        }
    }

    // ==================== 包操作 ====================

    /** 解压 zip 到 [targetDir]；含 zip-slip 路径防护与解压总量上限。 */
    private fun extractZip(context: Context, uri: Uri, targetDir: File) {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw InstallException("安装失败：无法读取所选文件")
        targetDir.mkdirs()
        var total = 0L
        input.use { raw ->
            ZipInputStream(raw.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val out = File(targetDir, entry.name)
                    if (!out.canonicalPath.startsWith(targetDir.canonicalPath + File.separator) &&
                        out.canonicalPath != targetDir.canonicalPath
                    ) {
                        throw InstallException("安装失败：插件包包含非法路径（${entry.name}）")
                    }
                    if (entry.isDirectory) {
                        out.mkdirs()
                        continue
                    }
                    out.parentFile?.mkdirs()
                    java.io.FileOutputStream(out).use { fos ->
                        val buf = ByteArray(64 * 1024)
                        var n = zip.read(buf)
                        while (n > 0) {
                            total += n
                            if (total > MAX_UNZIP_BYTES) {
                                throw InstallException("安装失败：插件包体积超出限制")
                            }
                            fos.write(buf, 0, n)
                            n = zip.read(buf)
                        }
                    }
                }
            }
        }
    }

    /** plugin.xml 所在目录：包根，或唯一一级子目录（兼容打包工具包裹一层根目录的情况）。 */
    private fun locateRoot(tmp: File): File? {
        if (File(tmp, "plugin.xml").isFile) return tmp
        val children = tmp.listFiles { f -> f.isDirectory } ?: return null
        if (children.size == 1 && File(children[0], "plugin.xml").isFile) return children[0]
        return null
    }

    private fun copyDir(src: File, dest: File) {
        if (src.isFile) {
            src.copyTo(File(dest, src.name), overwrite = true)
            return
        }
        dest.mkdirs()
        src.listFiles()?.forEach { copyDir(it, File(dest, it.name)) }
    }
}
