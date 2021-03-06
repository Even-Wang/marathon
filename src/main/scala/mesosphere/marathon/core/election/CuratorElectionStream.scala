package mesosphere.marathon
package core.election

import akka.actor.Cancellable
import akka.stream.scaladsl.{Source, SourceQueueWithComplete}
import akka.stream.OverflowStrategy
import com.typesafe.scalalogging.StrictLogging
import java.util
import java.util.Collections
import java.util.concurrent.TimeUnit

import mesosphere.marathon.metrics.{Metrics, Timer}
import mesosphere.marathon.metrics.deprecated.ServiceMetric
import mesosphere.marathon.stream.EnrichedFlow
import mesosphere.marathon.util.LifeCycledCloseableLike
import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.api.{ACLProvider, CuratorWatcher, UnhandledErrorListener}
import org.apache.curator.framework.imps.CuratorFrameworkState
import org.apache.curator.framework.recipes.leader.LeaderLatch
import org.apache.curator.framework.CuratorFrameworkFactory
import org.apache.curator.retry.ExponentialBackoffRetry
import org.apache.zookeeper.{KeeperException, WatchedEvent, ZooDefs}
import org.apache.zookeeper.data.ACL

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext
import scala.util.Try

object CuratorElectionStream extends StrictLogging {
  /**
    * Connects to Zookeeper and offers leadership; monitors leader state. Watches for leadership changes (leader
    * changed, was elected leader, lost leadership), and emits events accordingly.
    *
    * Materialized cancellable is used to abdicate leadership; which will do so followed by a closing of the stream.
    */
  def apply(
    metrics: Metrics,
    clientCloseable: LifeCycledCloseableLike[CuratorFramework],
    zooKeeperLeaderPath: String,
    zooKeeperConnectionTimeout: FiniteDuration,
    hostPort: String,
    singleThreadEC: ExecutionContext): Source[LeadershipState, Cancellable] = {
    Source.queue[LeadershipState](16, OverflowStrategy.dropHead)
      .mapMaterializedValue { sq =>
        val emitterLogic = new CuratorEventEmitter(metrics, singleThreadEC, clientCloseable, zooKeeperLeaderPath, hostPort, sq)
        emitterLogic.start()
        sq.watchCompletion().onComplete { _ => emitterLogic.cancel() }(singleThreadEC)
        emitterLogic
      }
      .initialTimeout(zooKeeperConnectionTimeout)
      .concat(Source.single(LeadershipState.Standby(None)))
      .via(EnrichedFlow.dedup(LeadershipState.Standby(None)))
      .map { e =>
        e match {
          case LeadershipState.ElectedAsLeader =>
            logger.info(s"Leader won: ${hostPort}")
          case LeadershipState.Standby(None) =>
            logger.info("Leader unknown.")
          case LeadershipState.Standby(Some(currentLeader)) =>
            logger.info(s"Leader defeated. Current leader: ${currentLeader}")
        }
        e
      }
  }

  private class CuratorEventEmitter(
      metrics: Metrics,
      singleThreadEC: ExecutionContext,
      clientCloseable: LifeCycledCloseableLike[CuratorFramework],
      zooKeeperLeaderPath: String,
      hostPort: String,
      sq: SourceQueueWithComplete[LeadershipState]) extends Cancellable {

    val client = clientCloseable.closeable
    private lazy val oldLeaderHostPortMetric: Timer =
      metrics.deprecatedTimer(ServiceMetric, getClass, "current-leader-host-port")
    private lazy val newLeaderHostPortMetric: Timer =
      metrics.timer("debug.current-leader.retrieval.duration")
    private val curatorLeaderLatchPath = zooKeeperLeaderPath + "-curator"
    private lazy val latch = new LeaderLatch(client, curatorLeaderLatchPath, hostPort)
    private var isStarted = false
    @volatile private var _isCancelled = false

    /* Long-poll trampoline-style recursive method which calls emitLeader() each time it detects that the leadership
     * state has changed.
     *
     * Given instance A, B, C, Curator's Leader latch recipe only provides A the ability to be notified if it gains or
     * loses leadership, but not if leadership transitions between B and C. This method allows us to monitor any change
     * in leadership state.
     *
     * It is important that we re-register our watch _BEFORE_ we get the current leader. In Zookeeper,
     * watches are one-time use only.
     *
     * The timeline of events looks like this
     *
     * 1) We register a watch
     * 2) We query the current leader
     * 3) We receive an event (indicating child removal / addition)
     * 4) Repeat step 1
     *
     * By re-registering the watch before querying the state, we will not miss out on the latest leader change.
     *
     * We also have a retry and (very simple) back-off mechanism. This is because Curator's leader latch creates the
     * initial leader node asynchronously. If we poll for leader information before this background hook completes, then
     * a KeeperException.NoNodeException is thrown (which we handle, and retry)
     */
    def longPollLeaderChange(retries: Int = 0): Unit = singleThreadEC.execute { () =>
      try {
        if (latch.getState == LeaderLatch.State.STARTED)
          client.getChildren
            .usingWatcher(new CuratorWatcher {
              override def process(event: WatchedEvent): Unit =
                if (!_isCancelled) longPollLeaderChange()
            })
            .forPath(curatorLeaderLatchPath)
        emitLeader()
      } catch {
        case ex: KeeperException.NoNodeException if retries < 100 =>
          // Wait for node to be created
          logger.info("retrying")
          Thread.sleep(retries * 10L)
          longPollLeaderChange(retries + 1)
        case ex: Throwable =>
          sq.fail(ex)
      }
    }

    /**
      * Emit current leader. Does not fail on connection error, but throws if multiple election candidates have the same
      * ID.
      */
    private def emitLeader(): Unit = {
      val participants = oldLeaderHostPortMetric.blocking {
        newLeaderHostPortMetric.blocking {
          try {
            if (client.getState == CuratorFrameworkState.STOPPED)
              Nil
            else
              latch.getParticipants.asScala.toList
          } catch {
            case ex: Throwable =>
              logger.error("Error while getting current leader", ex)
              Nil
          }
        }
      }

      val selfParticipantCount = participants.iterator.count(_.getId == hostPort)
      if (selfParticipantCount == 1) {
        val element = participants.find(_.isLeader).map(_.getId) match {
          case Some(leader) if leader == hostPort => LeadershipState.ElectedAsLeader
          case otherwise => LeadershipState.Standby(otherwise)
        }
        sq.offer(element)
      } else if (selfParticipantCount > 1)
        throw new IllegalStateException(s"Multiple election participants have the same ID: ${hostPort}. This is not allowed.")
      else {
        /* If our participant record isn't in the list yet, emit nothing. Curator Latch is still initializing.
         *
         * This makes the election stream more deterministic.
         */
      }
    }

    private val closeHook: () => Unit = { () => cancel() }

    def start(): Unit = synchronized {
      require(!isStarted, "already started")
      isStarted = true
      // We register the beforeClose hook to ensure that we have an opportunity to remove the latch entry before we lose
      // our connection to Zookeeper
      clientCloseable.beforeClose(closeHook)
      try {
        logger.info("starting leader latch")
        latch.start()
        longPollLeaderChange()

      } catch {
        case ex: Throwable =>
          logger.error("Error starting curator election event emitter")
          sq.fail(ex)
      }
    }

    override def isCancelled: Boolean = _isCancelled

    override def cancel(): Boolean = synchronized {
      require(isStarted, "not started")
      if (!_isCancelled) {
        clientCloseable.removeBeforeClose(closeHook)
        _isCancelled = true
        // shutdown hook remove will throw if already shutting down; swallow the exception and continue.

        try {
          logger.info("Closing leader latch")
          latch.close()
          logger.info("Leader latch closed")
        } catch {
          case ex: Throwable =>
            logger.error("Error closing CuratorElectionStream latch", ex)
        }
        Try(sq.complete()) // if not already completed
      }
      _isCancelled
    }
  }

  def newCuratorConnection(zkUrl: ZookeeperConf.ZkUrl, sessionTimeoutMs: Int, connectionTimeoutMs: Int,
    timeoutDurationMs: Int, defaultCreationACL: util.ArrayList[ACL]) = {
    logger.info(s"Will do leader election through ${zkUrl.redactedConnectionString}")

    // let the world read the leadership information as some setups depend on that to find Marathon
    val defaultAcl = new util.ArrayList[ACL]()
    defaultAcl.addAll(defaultCreationACL)
    defaultAcl.addAll(ZooDefs.Ids.READ_ACL_UNSAFE)

    val aclProvider = new ACLProvider {
      override def getDefaultAcl: util.List[ACL] = defaultAcl
      override def getAclForPath(path: String): util.List[ACL] = defaultAcl
    }

    val retryPolicy = new ExponentialBackoffRetry(1.second.toMillis.toInt, 10)
    val builder = CuratorFrameworkFactory.builder().
      connectString(zkUrl.hostsString).
      sessionTimeoutMs(sessionTimeoutMs).
      connectionTimeoutMs(connectionTimeoutMs).
      aclProvider(aclProvider).
      retryPolicy(retryPolicy)

    // optionally authenticate
    zkUrl.credentials.foreach { credentials =>
      builder.authorization(Collections.singletonList(credentials.authInfoDigest))
    }
    val client = builder.build()

    val listener = new LastErrorListener
    client.getUnhandledErrorListenable().addListener(listener)
    client.start()
    if (!client.blockUntilConnected(timeoutDurationMs, TimeUnit.MILLISECONDS)) {
      // If we couldn't connect, throw any errors that were reported
      listener.lastError.foreach { e => throw e }
    }

    client.getUnhandledErrorListenable().removeListener(listener)
    client
  }

  private class LastErrorListener extends UnhandledErrorListener {
    private[this] var _lastError: Option[Throwable] = None
    override def unhandledError(message: String, e: Throwable): Unit = {
      _lastError = Some(e)
    }

    def lastError = _lastError
  }
}
