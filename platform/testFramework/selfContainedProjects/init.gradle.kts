//
// Intercepts Maven repository requests through a local HTTP proxy
// with caching support for air-gapped/offline builds.
//
// Set env variable SELF_CONTAINED_PROXY_URL to the base URL of the proxy server.
// Set SELF_CONTAINED_VERBOSE to 'true' to see detailed logs on what URLs are being proxied.
//

import com.sun.net.httpserver.HttpServer
import com.sun.net.httpserver.HttpExchange
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantLock
import org.gradle.api.artifacts.repositories.MavenArtifactRepository

val PROXY_URL_PROPERTY = "SELF_CONTAINED_PROXY_URL"
val VERBOSE_PROPERTY = "SELF_CONTAINED_VERBOSE"

// --- Configuration ---
val proxyUrl = (System.getenv(PROXY_URL_PROPERTY) ?: error("$PROXY_URL_PROPERTY must be set")).also {
  check(!it.endsWith("/")) { "Proxy URL must not end with /" }
}
val verboseLogging = System.getenv(VERBOSE_PROPERTY)?.toBoolean() ?: true

// --- Logging ---
fun log(message: String) = System.err.println("[SELF-CONTAINED] $message")
fun logVerbose(message: String) {
  if (verboseLogging) log(message)
}

// --- Repository URL Rewriting ---
fun rewriteUrl(originalUrl: URI): URI {
  if (originalUrl.scheme != "https") {
    error("non-HTTPS URLs are unsupported: $originalUrl")
  }

  val newUrl = "$proxyUrl/${originalUrl.host}${originalUrl.path}"
  logVerbose("Rewriting $originalUrl -> $newUrl")
  return URI(newUrl)
}

fun rewriteRepositories(repositories: RepositoryHandler, context: String) {
  repositories.all {
    if (this is MavenArtifactRepository) {
      val original = url
      val newUrl = rewriteUrl(original)
      url = newUrl
      logVerbose("[$context] Rewrote: $original -> $newUrl")
    }
  }
}

// --- Gradle Hooks ---

// Use beforeSettings to intercept repositories AS THEY ARE ADDED.
// This is critical because plugin resolution happens DURING settings.gradle.kts evaluation,
// before settingsEvaluated fires. Using repositories.all {} sets up a listener that
// intercepts each repository when it's added.
beforeSettings {
  // Intercept pluginManagement repositories as they are added
  pluginManagement.repositories.all {
    if (this is MavenArtifactRepository) {
      val original = url
      url = rewriteUrl(original)
      logVerbose("[pluginManagement] Rewrote: $original -> $url")
    }
  }

  // Intercept dependencyResolutionManagement repositories as they are added
  @Suppress("UnstableApiUsage")
  dependencyResolutionManagement.repositories.all {
    if (this is MavenArtifactRepository) {
      val original = url
      url = rewriteUrl(original)
      logVerbose("[dependencyResolutionManagement] Rewrote: $original -> $url")
    }
  }
}

// Hook into all projects to rewrite project-level repositories
gradle.allprojects {
  afterEvaluate {
    repositories.all {
      if (this is MavenArtifactRepository) {
        val original = url
        url = rewriteUrl(original)
        logVerbose("[project:${project.name}] Rewrote: $original -> $url")
      }
    }
  }
}

log("Init script loaded. All repository URLs will be proxied through $proxyUrl")
