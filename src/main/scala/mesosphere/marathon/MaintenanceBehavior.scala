package mesosphere.marathon

sealed trait MaintenanceBehavior
object MaintenanceBehavior {
  case object DeclineOffers extends MaintenanceBehavior
  case object Ignore extends MaintenanceBehavior
}
