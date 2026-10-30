package gears.async

import language.experimental.captureChecking
import caps.*

/** A trait for cancellable entities that can be grouped. */
trait Cancellable:

  private var group: CompletionGroup = scala.compiletime.uninitialized

  /** Issue a cancel request. */
  def cancel(): Unit

  /** Add this cancellable to the given group after removing it from the previous group in which it was.
    *
    * **CC safety note**: at the moment, linking and unlinking to different [[CompletionGroup CompletionGroups]] will bypass
    * static scope safety checks. Thus, these operations are considered capture-unsafe, and must be used with caution.
    * Specifically, `unsafe` must be used to cast the [[CompletionGroup]] to a non-capturing one before calling.
    */
  def link(group: CompletionGroup): this.type =
    synchronized:
      if this.group != null then
        this.group.drop(this)
      // SAFETY: see note above.
      this.group = group
      this.group.add(this)
      this
    .asInstanceOf[this.type]

  /** Link this cancellable to the cancellable group of the current async context.
    */
  private[gears] def link()(using async: Async^): this.type =
    // SAFETY: this is only used in the implementation, where cancellable instances _already_ capture the Async instance.
    link(unsafe.unsafeAssumePure(async.group))

  /** Unlink this cancellable from its group.
    *
    * **CC safety note**: see [[link]].
    */
  def unlink(): this.type =
    link(CompletionGroup.Unlinked)

end Cancellable

object Cancellable:
  /** A special [[Cancellable]] object that just tracks whether its linked group was cancelled. */
  trait Tracking extends Cancellable:
    def isCancelled: Boolean

  object Tracking:
    def apply() = new Tracking:
      private var cancelled: Boolean = false

      def cancel(): Unit =
        cancelled = true

      def isCancelled = cancelled
end Cancellable
