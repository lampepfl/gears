package gears.async
import scala.collection.mutable
import Future.Promise
import scala.util.Success

/** A group of cancellable objects that are completed together.
 *  Cancelling the group means cancelling all its uncompleted members.
 *  @param  handleCompletion  a function that gets applied to every member
 *                            when it is completed or cancelled
 */
class CompletionGroup(val handleCompletion: Cancellable => Async ?=> Unit = _ => {}) extends Cancellable.Tracking:
  private val members: mutable.Set[Cancellable] = mutable.Set()
  private var canceled: Boolean = false
  private var cancelWait: Option[Promise[Unit]] = None

  /** Cancel all members */
  def cancel(): Unit =
    synchronized:
      if canceled then Seq.empty
      else
        canceled = true
        members.toSeq
    .foreach(_.cancel())

  private[async] def waitCompletion()(using Async): Unit =
    synchronized:
      if members.nonEmpty && cancelWait.isEmpty then
        cancelWait = Some(Promise())
    cancelWait.foreach(cWait => Async.await(cWait.future))
    signalCompletion()

  /** Add given member to the members set. If the group has already been cancelled, cancels that member immediately. */
  def add(member: Cancellable): Unit =
    val alreadyCancelled = synchronized:
      members += member // Add this member no matter what since we'll wait for it still
      canceled
    if alreadyCancelled then member.cancel()

  /** Remove given member from the members set if it is an element */
  def drop(member: Cancellable): Unit = synchronized:
    members -= member
    if members.isEmpty && cancelWait.isDefined then
      cancelWait.get.complete(Success(()))

  def isCancelled = canceled

object CompletionGroup:

  /** A sentinel group of cancellables that are in fact not linked
   *  to any real group. `cancel`, `add`, and `drop` do nothing when
   *  called on this group.
   */
  object Unlinked extends CompletionGroup:
    override def cancel(): Unit = ()
    override def waitCompletion()(using Async): Unit = ()
    override def add(member: Cancellable): Unit = ()
    override def drop(member: Cancellable): Unit = ()
  end Unlinked

end CompletionGroup
