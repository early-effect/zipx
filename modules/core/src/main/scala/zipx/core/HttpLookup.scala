package zipx.core

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.format.DateTimeFormatter
import java.time.{Duration as JDuration, ZonedDateTime}
import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters.*
import zio.*

/** One HTTP GET/POST with first-attempt jitter, exponential retry, and Retry-After.
  *
  * sbt tasks stay `Either[String, A]`. Tests inject `send`, `retry`, and `firstJitter` and drive ZIO `Clock` / `Random`
  * so they never hit the network or sleep wall-clock time.
  */
final case class HttpLookupResult(
    status: Int,
    body: String,
    headers: Map[String, String],
):
  def header(name: String): Option[String] =
    headers.collectFirst { case (k, v) if k.equalsIgnoreCase(name) => v }

  def etag: Option[String]       = header("ETag")
  def retryAfter: Option[String] = header("Retry-After")
  def notModified: Boolean       = status == 304
  def isMiss: Boolean            = status == 404 || status == 410 || status == 304
end HttpLookupResult

object HttpLookup:

  type Send = HttpRequest => Task[HttpLookupResult]

  val ConnectTimeout: JDuration                          = JDuration.ofSeconds(10)
  val DefaultTimeout: JDuration                          = JDuration.ofSeconds(20)
  val FirstAttemptJitter: Duration                       = 250.millis
  val RetryAfterCap: Duration                            = 60.seconds
  val DefaultRetry: Schedule[Any, Any, (Duration, Long)] =
    (Schedule.exponential(100.millis) && Schedule.recurs(5)).jittered

  private val sharedEtags = new ConcurrentHashMap[String, String]()

  private val client: HttpClient =
    HttpClient.newBuilder().connectTimeout(ConnectTimeout).build()

  /** Where a 3xx GET keeps the bytes. GitHub Packages answers 302 for an authenticated jar and puts the artifact at
    * `Location`, a pre-signed URL on another host. `None` means this status is the answer, including a 302 with no
    * location.
    */
  def redirectTarget(status: Int, location: Option[String], requestUrl: String): Option[String] =
    status match
      case 301 | 302 | 303 | 307 | 308 =>
        location.filter(_.nonEmpty).map(loc => URI.create(requestUrl).resolve(loc).toString)
      case _ => None

  def get(
      url: String,
      headers: Map[String, String] = Map.empty,
      timeout: JDuration = DefaultTimeout,
      ifNoneMatch: Option[String] = None,
      send: Send = jdkSend,
      retry: Schedule[Any, Any, ?] = DefaultRetry,
      firstJitter: Duration = FirstAttemptJitter,
      etags: ConcurrentHashMap[String, String] = sharedEtags,
  ): Either[String, HttpLookupResult] =
    if url.startsWith("file:") then Right(readFile(url))
    else runEither(getZio(url, headers, timeout, ifNoneMatch, send, retry, firstJitter, etags))

  /** A `file:` registry, for a release or snapshot rehearsed entirely on one machine. */
  private def readFile(url: String): HttpLookupResult =
    val path = java.nio.file.Path.of(URI.create(url))
    if java.nio.file.Files.isRegularFile(path) then
      HttpLookupResult(200, java.nio.file.Files.readString(path), Map.empty)
    else if path.getFileName.toString == "maven-metadata.xml" then
      publishedVersions(path.getParent) match
        case Nil      => HttpLookupResult(404, "", Map.empty)
        case versions =>
          val listed = versions.map(v => s"<version>$v</version>").mkString
          HttpLookupResult(
            200,
            s"<metadata><versioning><versions>$listed</versions></versioning></metadata>",
            Map.empty,
          )
    else HttpLookupResult(404, "", Map.empty)
    end if
  end readFile

  /** sbt writes no `maven-metadata.xml` into a `file:` repository, so a version is a directory holding a POM. */
  private def publishedVersions(artifactDir: java.nio.file.Path): List[String] =
    def children(dir: java.nio.file.Path): List[java.nio.file.Path] =
      if !java.nio.file.Files.isDirectory(dir) then Nil
      else scala.util.Using.resource(java.nio.file.Files.list(dir))(_.iterator.asScala.toList)
    children(artifactDir)
      .filter(version => children(version).exists(_.getFileName.toString.endsWith(".pom")))
      .map(_.getFileName.toString)
      .sorted

  def post(
      url: String,
      body: String,
      headers: Map[String, String] = Map.empty,
      timeout: JDuration = DefaultTimeout,
      send: Send = jdkSend,
      retry: Schedule[Any, Any, ?] = DefaultRetry,
      firstJitter: Duration = FirstAttemptJitter,
  ): Either[String, HttpLookupResult] =
    runEither(postZio(url, body, headers, timeout, send, retry, firstJitter))

  private[core] def getZio(
      url: String,
      headers: Map[String, String] = Map.empty,
      timeout: JDuration = DefaultTimeout,
      ifNoneMatch: Option[String] = None,
      send: Send = jdkSend,
      retry: Schedule[Any, Any, ?] = DefaultRetry,
      firstJitter: Duration = FirstAttemptJitter,
      etags: ConcurrentHashMap[String, String] = sharedEtags,
  ): IO[String, HttpLookupResult] =
    val inm = ifNoneMatch.orElse(Option(etags.get(url))).filter(_.nonEmpty)
    val req = request(url, headers, timeout, body = None, ifNoneMatch = inm)
    execute(url, req, send, retry, firstJitter, Some(etags))
  end getZio

  private[core] def postZio(
      url: String,
      body: String,
      headers: Map[String, String] = Map.empty,
      timeout: JDuration = DefaultTimeout,
      send: Send = jdkSend,
      retry: Schedule[Any, Any, ?] = DefaultRetry,
      firstJitter: Duration = FirstAttemptJitter,
  ): IO[String, HttpLookupResult] =
    val req = request(url, headers, timeout, body = Some(body), ifNoneMatch = None)
    execute(url, req, send, retry, firstJitter, etags = None)
  end postZio

  private[core] def jdkSend(req: HttpRequest): Task[HttpLookupResult] =
    ZIO.attemptBlocking {
      val res     = client.send(req, HttpResponse.BodyHandlers.ofString())
      val headers = res
        .headers()
        .map()
        .asScala
        .flatMap { (k, vs) =>
          vs.asScala.headOption.map(k -> _)
        }
        .toMap
      HttpLookupResult(res.statusCode(), Option(res.body()).getOrElse(""), headers)
    }

  private def execute(
      url: String,
      req: HttpRequest,
      send: Send,
      retry: Schedule[Any, Any, ?],
      firstJitter: Duration,
      etags: Option[ConcurrentHashMap[String, String]],
  ): IO[String, HttpLookupResult] =
    val once: IO[LookupFailure, HttpLookupResult] =
      send(req).mapError(throwableFailure).flatMap(classify(_, url, etags))
    val policy = retry.whileInput[LookupFailure] {
      case LookupFailure.Retryable(_, _) => true
      case LookupFailure.Fatal(_)        => false
    }
    jitter(firstJitter) *>
      once.retry(policy).mapError {
        case LookupFailure.Retryable(msg, _) => msg
        case LookupFailure.Fatal(msg)        => msg
      }
  end execute

  private def classify(
      result: HttpLookupResult,
      url: String,
      etags: Option[ConcurrentHashMap[String, String]],
  ): IO[LookupFailure, HttpLookupResult] =
    result.status match
      case s if s >= 200 && s < 300 =>
        etags.foreach { map =>
          result.etag.foreach(tag => map.put(url, tag))
        }
        ZIO.succeed(result)
      case 304 | 404 | 410 =>
        ZIO.succeed(result)
      case s if s == 429 || s >= 500 =>
        retryAfterDelay(result).flatMap { wait =>
          val err = LookupFailure.Retryable(s"HTTP $s", wait)
          wait match
            case Some(d) if d > Duration.Zero => ZIO.sleep(d) *> ZIO.fail(err)
            case _                            => ZIO.fail(err)
        }
      case s =>
        ZIO.fail(LookupFailure.Fatal(s"HTTP $s"))

  private def retryAfterDelay(result: HttpLookupResult): UIO[Option[Duration]] =
    result.retryAfter match
      case None      => ZIO.succeed(None)
      case Some(raw) => parseRetryAfter(raw).map(_.map(_.min(RetryAfterCap)))

  private[core] def parseRetryAfter(raw: String): UIO[Option[Duration]] =
    raw.trim.toLongOption match
      case Some(secs) if secs < 0 => ZIO.succeed(Some(Duration.Zero))
      case Some(secs)             => ZIO.succeed(Some(secs.seconds))
      case None                   =>
        Clock.instant.map { now =>
          try
            val at = ZonedDateTime.parse(raw.trim, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant
            val d  = JDuration.between(now, at)
            if d.isNegative then Some(Duration.Zero)
            else Some(Duration.fromNanos(d.toNanos))
          catch case _: Exception => None
        }

  private def jitter(max: Duration): UIO[Unit] =
    if max <= Duration.Zero then ZIO.unit
    else Random.nextDouble.flatMap(d => ZIO.sleep(max * d))

  private def throwableFailure(t: Throwable): LookupFailure =
    if isRetryableThrowable(t) then LookupFailure.Retryable(Option(t.getMessage).getOrElse(t.getClass.getName), None)
    else LookupFailure.Fatal(Option(t.getMessage).getOrElse(t.getClass.getName))

  private def isRetryableThrowable(t: Throwable): Boolean =
    t match
      case _: java.net.http.HttpTimeoutException => true
      case _: java.net.ConnectException          => true
      case _: java.net.SocketTimeoutException    => true
      case e: java.net.SocketException           =>
        Option(e.getMessage).exists(m => m.toLowerCase.contains("reset") || m.toLowerCase.contains("broken pipe"))
      case _: java.io.IOException                     => true
      case e if e.getCause != null && e.getCause != e => isRetryableThrowable(e.getCause)
      case _                                          => false

  private def request(
      url: String,
      headers: Map[String, String],
      timeout: JDuration,
      body: Option[String],
      ifNoneMatch: Option[String],
  ): HttpRequest =
    val builder = HttpRequest.newBuilder(URI.create(url)).timeout(timeout)
    headers.foreach { (k, v) => builder.header(k, v) }
    ifNoneMatch.foreach(tag => builder.header("If-None-Match", tag))
    body match
      case Some(bytes) => builder.POST(HttpRequest.BodyPublishers.ofString(bytes)).build()
      case None        => builder.GET().build()
  end request

  private def runEither[A](effect: IO[String, A]): Either[String, A] =
    try
      Unsafe.unsafe { implicit u =>
        Runtime.default.unsafe.run(effect.either).getOrThrowFiberFailure()
      }
    catch case e: Exception => Left(Option(e.getMessage).getOrElse(e.getClass.getName))

  private enum LookupFailure:
    case Retryable(message: String, retryAfter: Option[Duration])
    case Fatal(message: String)
end HttpLookup
