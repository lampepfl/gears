package gears.async
import gears.async.io.internal.CancellationSnapshot

import java.util.IdentityHashMap
import scala.jdk.CollectionConverters.*
import scala.util.Success

import Future.Promise

/** A group of cancellable objects that are completed together. Cancelling the group means cancelling all its
  * uncompleted members. Membership uses object identity, independent of a member's equality or mutable hash.
  */
class CompletionGroup extends Cancellable.Tracking:
  // A group mostly has at most one member at a time, such as a future's wait in progress, so a member added while
  // `single` is free goes there, which costs no allocation to add or drop, and `members` holds the rest.
  private var single: Cancellable | Null = null
  // Membership is by identity: operations can be case classes containing buffers whose hash changes during I/O.
  private var members: IdentityHashMap[Cancellable, java.lang.Boolean] | Null = null
  @volatile private var canceled: Boolean = false
  private var cancelWait: Option[Promise[Unit]] = None

  private def isEmpty: Boolean = single == null && (members == null || members.nn.isEmpty)

  // Capture reusable members while unlink is excluded. Saving just the member lets a delayed cancel target its next use.
  private def cancellationOf(member: Cancellable): Cancellable = member match
    case reusable: CancellationSnapshot => reusable.snapshotCancellation()
    case _                              => member

  /** Cancel all members */
  def cancel(): Unit =
    synchronized:
      if canceled then Seq.empty
      else
        canceled = true
        val rest =
          if members == null then Seq.empty else members.nn.keySet().iterator().asScala.map(cancellationOf).toList
        if single == null then rest else cancellationOf(single.nn) +: rest
    .foreach(_.cancel())

  /** Wait for all members of the group to complete and unlink themselves. */
  private[async] def waitCompletion()(using Async): Unit =
    synchronized:
      if !isEmpty && cancelWait.isEmpty then cancelWait = Some(Promise())
    cancelWait.foreach(cWait => cWait.await)
    unlink()

  /** Add given member to the members set. If the group has already been cancelled, cancels that member immediately. */
  def add(member: Cancellable): Unit =
    val cancellation = synchronized:
      // Add this member no matter what since we'll wait for it still
      if single == null then
        if members == null || !members.nn.containsKey(member) then single = member
      else if single.nn ne member then
        if members == null then members = new IdentityHashMap(4)
        members.nn.put(member, java.lang.Boolean.TRUE)
      if canceled then cancellationOf(member) else null
    if cancellation != null then cancellation.cancel()

  /** Remove given member from the members set if it is an element */
  def drop(member: Cancellable): Unit = synchronized:
    if single != null && (single.nn eq member) then single = null
    else if members != null then members.nn.remove(member)
    if isEmpty && cancelWait.isDefined then cancelWait.get.complete(Success(()))

  def isCancelled = canceled

object CompletionGroup:
  /** A sentinel group of cancellables that are in fact not linked to any real group. `cancel`, `add`, and `drop` do
    * nothing when called on this group.
    */
  object Unlinked extends CompletionGroup:
    override def cancel(): Unit = ()
    override def waitCompletion()(using Async): Unit = ()
    override def add(member: Cancellable): Unit = ()
    override def drop(member: Cancellable): Unit = ()
  end Unlinked

end CompletionGroup
