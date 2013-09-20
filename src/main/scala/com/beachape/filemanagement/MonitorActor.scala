package com.beachape.filemanagement

import akka.actor.{Actor, Props}
import akka.routing.SmallestMailboxRouter
import com.beachape.filemanagement.Messages._
import com.beachape.filemanagement.RegistryTypes._
import com.typesafe.scalalogging.slf4j.Logging
import java.nio.file.StandardWatchEventKinds._
import java.nio.file._
import scala.collection.mutable
import scala.language.existentials

/**
 * Companion object for creating Monitor actor instances
 */
object MonitorActor {

  /**
   * Factory method for the params required to instantiate a MonitorActor
   *
   * @param concurrency Integer, the number of concurrent threads for handling callbacks
   * @return Props for instantiating a MonitorActor
   */
  def apply(concurrency: Int = 5) = {
    require(concurrency > 1, s"Callback concurrency requested is $concurrency but it should at least be 1")
    Props(classOf[MonitorActor], concurrency)
  }
}

/**
 * Actor for registering feedbacks and delegating callback execution
 *
 * Should be instantiated with Props provided via companion object factory
 * method
 */
class MonitorActor(concurrency: Int = 5) extends Actor with Logging with RecursiveFileActions {

  // Smallest mailbox router for callback actors
  private[this] val callbackActors = context.actorOf(
    CallbackActor().withRouter(SmallestMailboxRouter(concurrency)), "callbackActors")

  private[this] val eventTypeCallbackRegistryMap = mutable.Map(
    ENTRY_CREATE -> CallbackRegistry(ENTRY_CREATE),
    ENTRY_MODIFY -> CallbackRegistry(ENTRY_MODIFY),
    ENTRY_DELETE -> CallbackRegistry(ENTRY_DELETE))

  private[this] val watchServiceTask = new WatchServiceTask(self)
  private[this] val watchThread = new Thread(watchServiceTask, "WatchService")

  override def preStart() = {
    watchThread.setDaemon(true)
    watchThread.start()
  }

  override def postStop() = {
    watchThread.interrupt()
  }

  def receive = {
    case EventAtPath(event, path) =>
      // Ensure that only absolute paths are used
      val absolutePath = path.toAbsolutePath
      logger.info(s"Event $event at path: $path")
      processCallbacksForEventPath(event.asInstanceOf[WatchEvent.Kind[Path]], path)

    case RegisterCallback(event, recursive, path, callback) =>
      // Ensure that only absolute paths are used
      val absolutePath = path.toAbsolutePath
      if (recursive) {
        recursivelyAddPathCallback(event, absolutePath, callback)
        recursivelyAddPathToWatchServiceTask(event, absolutePath)
      } else {
        addPathCallback(event, absolutePath, callback)
        addPathToWatchServiceTask(event, absolutePath)
      }

    case UnRegisterCallback(event, recursive, path) =>
      // Ensure that only absolute paths are used
      val absolutePath = path.toAbsolutePath
      if (recursive) {
        recursivelyRemoveCallbacksForPath(event, absolutePath)
      } else {
        removeCallbacksForPath(event, absolutePath)
      }

    case _ => logger.error("Monitor Actor received an unexpected message :( !")
  }

  /**
    * Modify the CallbackRegistry for a given Event type
    *
    * @param eventType WatchEvent.Kind[Path] Java7 Event type
    * @param modify a function to update the CallbackRegistry
    * @return Unit
    */
  private[this] def modifyCallbackRegistry(eventType: WatchEvent.Kind[Path]
                                          ,modify: CallbackRegistry => CallbackRegistry): Unit = {
    eventTypeCallbackRegistryMap.get(eventType) foreach { registry =>
      eventTypeCallbackRegistryMap.update(eventType, modify(registry))
    }
  }

  /**
   * Registers a callback for a path for an Event type
   *
   * If the path does not exist at the time of adding, a log message is created and the
   * path is ignored
   *
   * @param eventType WatchEvent.Kind[Path] Java7 Event type
   * @param path Path (Java type) to be registered
   * @param callback Callback function, Path => Init
   * @return Path used for registration
   */
  private[this] def addPathCallback(eventType: WatchEvent.Kind[Path], path: Path, callback: Callback): Path = {
    modifyCallbackRegistry(eventType, { _ withPathCallback(path, callback) })
    path
  }

  /**
   * Recursively registers a callback for a path for an Event type.
   * Only recursively registers callbacks for paths that are directories including the current path.
   *
   * If the path is not that of a directory, register the callback only for the path itself
   * then move on. Only recursively registers callbacks for paths that are directories including the current path.
   *
   * @param eventType WatchEvent.Kind[Path] Java7 Event type
   * @param path Path (Java type) to be registered
   * @param callback Callback function, Path => Init
   * @return Path used for registration
   */
  private[this] def recursivelyAddPathCallback(eventType: WatchEvent.Kind[Path], path: Path, callback: Callback): Path = {
    modifyCallbackRegistry(eventType, { _ withPathCallbackRecursive(path, callback) })
    path
  }

  /**
   * Removes the callbacks for a specific path, does not care if Path had no callbacks in the
   * first place. Only recursively un-registers callbacks for paths that are directories under the current path.
   *
   * Note that this does not remove the event listeners from the Java API,
   * because such functionality does not exist. All this does is make sure that the callbacks
   * registered to a specific path do not get fired. Depending on your use case,
   * it may make more sense to just kill the monitor actor to start fresh.
   *
   * @param eventType WatchEvent.Kind[Path] Java7 Event type
   * @param path Path (Java type) to be registered
   * @return Path used for un-registering callbacks
   */
  private[this] def removeCallbacksForPath(eventType: WatchEvent.Kind[Path], path: Path): Path = {
    modifyCallbackRegistry(eventType, { _ withoutCallbacksForPath(path) })
    path
  }

  /**
   * Recursively removes the callbacks for a specific path, does not care if Path had no callbacks in the
   * first place. Only recursively un-registers callbacks for paths that are directories under the current path.
   *
   * Note that this does not remove the event listeners from the Java API,
   * because such functionality does not exist. All this does is make sure that the callbacks
   * registered to a specific path do not get fired. Depending on your use case,
   * it may make more sense to just kill the monitor actor to start fresh.
   *
   * @param eventType WatchEvent.Kind[Path] Java7 Event type
   * @param path Path (Java type) to be registered
   * @return Path used for un-registering callbacks
   */
  private[this] def recursivelyRemoveCallbacksForPath(eventType: WatchEvent.Kind[Path], path: Path): Path = {
    modifyCallbackRegistry(eventType, { _ withoutCallbacksForPathRecursive(path) })
    path
  }

  /**
   * Retrieves the callbacks registered for a path for an Event type
   *
   * Note that #await is called on the agent so that the thread blocks until all changes
   * have been made on the agent.
   *
   * @param eventType WatchEvent.Kind[Path] Java7 Event type
   * @param path Path (Java type) to be registered
   * @return Option[Callbacks] for the path at the event type (Option[List[Path => Unit]])
   */
  private[this] def callbacksForPath(eventType: WatchEvent.Kind[Path], path: Path): Option[Callbacks] = {
    eventTypeCallbackRegistryMap.get(eventType) flatMap { _ callbacksForPath(path) }
  }

  /**
   * Adds a path to be monitored by the Watch Service Task
   *
   * @param eventType WatchEvent.Kind[Path] Java7 Event type
   * @param path Path (Java type) to be registered
   */
  private[this] def addPathToWatchServiceTask(eventType: WatchEvent.Kind[Path], path: Path) {
    logger.debug("Adding path to WatchServiceTask")
    watchServiceTask.watch(path, eventType)
  }

  /**
   * Recursively adds a path to be monitored by the Watch Service Task
   *
   * @param eventType WatchEvent.Kind[Path] Java7 Event type
   * @param path Path (Java type) to be registered
   */
  private[this] def recursivelyAddPathToWatchServiceTask(eventType: WatchEvent.Kind[Path], path: Path) {
    addPathToWatchServiceTask(eventType, path)
    forEachDir(path) { (directory, _) => addPathToWatchServiceTask(eventType, directory) }
  }

  /**
   * Finds the callbacks for a given EventType and path and sends them all to
   * the CallbackActor pool to get processed
   *
   * @param event WatchEvent.Kind[Path] Java7 Event type
   * @param lookupPath Path (Java type) to be registered
   * @param causerPath Path (Java type) to be sent to the callback, defaults to lookupPath
   * @return Unit
   */
  private[this] def processCallbacksForEventPath(event: WatchEvent.Kind[Path], path: Path) {

    def processCallbacks(lookupPath: Path): Unit = {
        for {
          callbacks <- callbacksForPath(event, lookupPath)
          callback  <- callbacks
        } {
          logger.debug(s"Sending callback for path: $path")
          callbackActors ! PerformCallback(path, callback)
        }
    }

    processCallbacks(path)
    // If event is ENTRY_DELETE or the path is a file, check for callbacks that
    // need to be fired for the directory the file is in
    if (event == ENTRY_DELETE || path.toFile.isFile) processCallbacks(path.getParent)
  }
}
