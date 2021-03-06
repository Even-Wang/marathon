package mesosphere.marathon
package api

import javax.servlet.http.HttpServletRequest
import javax.ws.rs._
import javax.ws.rs.core.{Context, MediaType, Request, Response, Variant}
import akka.actor.ActorSystem
import ch.qos.logback.classic.{Level, Logger, LoggerContext}
import com.google.inject.Inject
import com.typesafe.config.{Config, ConfigRenderOptions}
import com.typesafe.scalalogging.StrictLogging
import com.wix.accord.Validator
import mesosphere.marathon.metrics.current.reporters.PrometheusReporter
import mesosphere.marathon.plugin.auth.AuthorizedResource.SystemConfig
import mesosphere.marathon.plugin.auth.{Authenticator, Authorizer, UpdateResource, ViewResource}
import mesosphere.marathon.raml.{LoggerChange, Raml}
import mesosphere.marathon.raml.MetricsConversion._
import org.slf4j.LoggerFactory
import play.api.libs.json.Json

import scala.concurrent.ExecutionContext
import stream.Implicits._
import com.wix.accord.dsl._

import scala.concurrent.duration._

/**
  * System Resource gives access to system level functionality.
  * All system resources can be protected via ACLs.
  */
@Path("")
class SystemResource @Inject() (val config: MarathonConf, val metricsModule: MetricsModule, cfg: Config)(implicit
    val authenticator: Authenticator,
    val authorizer: Authorizer,
    actorSystem: ActorSystem,
    val executionContext: ExecutionContext) extends RestResource with AuthResource with StrictLogging {

  private[this] val TEXT_WILDCARD_TYPE = MediaType.valueOf("text/*")

  /**
    * If the user requests '/', redirect them to the UI
    */
  @GET
  @Path("")
  def redirectUI(): Response = {
    Response.status(302).header("Location", "/ui/").build
  }

  /**
    * ping sends a pong to a client.
    *
    * ping doesn't use the `Produces` or `Consumes` tags because those do specific checking for a client
    * Accept header that may or may not exist. In the interest of compatibility we dynamically generate
    * a "pong" content object depending on the client's preferred Content-Type, or else assume `text/plain`
    * if the client specifies an Accept header compatible with `text/{wildcard}`. Otherwise no entity is
    * returned and status is set to "no content" (HTTP 204).
    */
  @GET
  @Path("ping")
  def ping(@Context req: Request): Response = {
    import MediaType._

    val v = Variant.mediaTypes(
      TEXT_PLAIN_TYPE, // first, in case client accepts */* or text/*
      TEXT_HTML_TYPE,
      APPLICATION_JSON_TYPE,
      TEXT_WILDCARD_TYPE
    ).add.build

    Option[Variant](req.selectVariant(v)).map(variant => variant -> variant.getMediaType).collect {
      case (variant, mediaType) if mediaType.isCompatible(APPLICATION_JSON_TYPE) =>
        // return a properly formatted JSON object
        "\"pong\"" -> APPLICATION_JSON_TYPE
      case (variant, mediaType) if mediaType.isCompatible(TEXT_WILDCARD_TYPE) =>
        // otherwise we send back plain text
        "pong" -> {
          if (mediaType.isWildcardType() || mediaType.isWildcardSubtype()) {
            TEXT_PLAIN_TYPE // never return a Content-Type w/ a wildcard
          } else {
            mediaType
          }
        }
    }.map { case (obj, mediaType) => Response.ok(obj).`type`(mediaType.toString).build }.getOrElse {
      Response.noContent().build
    }
  }

  @GET
  @Path("metrics")
  @Consumes(Array(MediaType.APPLICATION_JSON))
  @Produces(Array(MediaType.APPLICATION_JSON))
  def metrics(@Context req: HttpServletRequest): Response = authenticated(req) { implicit identity =>
    withAuthorization(ViewResource, SystemConfig) {
      metricsModule.snapshot() match {
        case Left(snapshot) =>
          ok(jsonString(Raml.toRaml(snapshot)), MediaType.APPLICATION_JSON_TYPE)
        case Right(registry) =>
          ok(jsonString(Raml.toRaml(registry)), MediaType.APPLICATION_JSON_TYPE)
      }
    }
  }

  @GET
  @Path("config")
  @Consumes(Array(MediaType.APPLICATION_JSON))
  @Produces(Array(MediaType.APPLICATION_JSON))
  def config(@Context req: HttpServletRequest): Response = authenticated(req) { implicit identity =>
    withAuthorization(ViewResource, SystemConfig) {
      ok(cfg.root().render(ConfigRenderOptions.defaults().setJson(true)))
    }
  }

  @GET
  @Path("logging")
  @Consumes(Array(MediaType.APPLICATION_JSON))
  @Produces(Array(MediaType.APPLICATION_JSON))
  def showLoggers(@Context req: HttpServletRequest): Response = authenticated(req) { implicit identity =>
    withAuthorization(ViewResource, SystemConfig) {
      LoggerFactory.getILoggerFactory match {
        case lc: LoggerContext =>
          ok(lc.getLoggerList.map { logger =>
            logger.getName -> Option(logger.getLevel).map(_.levelStr).getOrElse(logger.getEffectiveLevel.levelStr + " (inherited)")
          }.toMap)
      }
    }
  }

  @POST
  @Path("logging")
  @Consumes(Array(MediaType.APPLICATION_JSON))
  @Produces(Array(MediaType.APPLICATION_JSON))
  def changeLogger(body: Array[Byte], @Context req: HttpServletRequest): Response = authenticated(req) { implicit identity =>
    withAuthorization(UpdateResource, SystemConfig) {
      withValid(Json.parse(body).as[LoggerChange]) { change =>
        LoggerFactory.getILoggerFactory.getLogger(change.logger) match {
          case logger: Logger =>
            val level = Level.valueOf(change.level.value.toUpperCase)

            // current level can be null, which means: use the parent level
            // the current level should be preserved, no matter what the effective level is
            val currentLevel = logger.getLevel
            val currentEffectiveLevel = logger.getEffectiveLevel
            logger.info(s"Set logger ${logger.getName} to $level current: $currentEffectiveLevel")
            logger.setLevel(level)

            // if a duration is given, we schedule a timer to reset to the current level
            change.durationSeconds.foreach(duration => actorSystem.scheduler.scheduleOnce(duration.seconds, new Runnable {
              override def run(): Unit = {
                logger.info(s"Duration expired. Reset Logger ${logger.getName} back to $currentEffectiveLevel")
                logger.setLevel(currentLevel)
              }
            }))
            ok(change)
        }
      }
    }
  }

  @GET
  @Path("metrics/prometheus")
  @Produces(Array(MediaType.TEXT_PLAIN))
  def metricsPrometheus(@Context req: HttpServletRequest): Response = authenticated(req) { implicit identity =>
    withAuthorization(ViewResource, SystemConfig) {
      if (config.isDeprecatedFeatureEnabled(DeprecatedFeatures.kamonMetrics)) {
        notFound("Prometheus reporter is not available with the deprecated metrics")
      } else if (!config.metricsPrometheusReporter()) {
        notFound("Prometheus reporter is disabled")
      } else {
        metricsModule.snapshot() match {
          case Right(registry) =>
            ok(PrometheusReporter.report(registry), MediaType.TEXT_PLAIN_TYPE)
          case _ =>
            notFound("Prometheus reporter is not available with the deprecated metrics")
        }
      }
    }
  }

  implicit lazy val validLoggerChange: Validator[LoggerChange] = validator[LoggerChange] { change =>
    change.logger is notEmpty
  }
}
